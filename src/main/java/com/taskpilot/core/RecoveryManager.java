package com.taskpilot.core;

import com.taskpilot.domain.*;
import com.taskpilot.llm.LlmClient;
import com.taskpilot.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Classifies failures and decides the recovery strategy.
 * Respects budgets: retries ≤ maxRetriesPerAction, replans ≤ maxReplans.
 */
@Component
public class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private static final String SYSTEM_PROMPT = """
            You are a recovery decision engine for an autonomous task worker.
            A tool action just failed. Decide the best recovery strategy.
            
            Options:
            - RETRY: Same action, transient failure (network timeout, temporary unavailable)
            - ALTERNATIVE: Try a different approach to achieve the same step (different tool or args)
            - REPLAN: The overall plan needs to change (unexpected situation, wrong direction)
            - ASK_USER: Human input is needed to proceed (missing data, ambiguous situation, critical decision)
            - ABORT: Unrecoverable failure (unknown tool, structural error, safety concern)
            
            Respond with ONLY the strategy name, e.g.: RETRY
            """;

    private final LlmClient llmClient;
    private final int maxRetriesPerAction;
    private final int maxReplans;

    public RecoveryManager(LlmClient llmClient,
                           @Value("${taskpilot.agent.max-retries-per-action:2}") int maxRetriesPerAction,
                           @Value("${taskpilot.agent.max-replans:3}") int maxReplans) {
        this.llmClient = llmClient;
        this.maxRetriesPerAction = maxRetriesPerAction;
        this.maxReplans = maxReplans;
    }

    public RecoveryDecision decide(TaskContext context, Observation failedObs, int retryCount) {
        String errorType = failedObs.toolResult() != null ? failedObs.toolResult().errorType() : "UNKNOWN";
        log.info("Recovery needed for task {}: errorType={}, retryCount={}", 
                context.task().getId(), errorType, retryCount);

        // Deterministic rules first (no LLM call needed)
        if ("UNKNOWN_TOOL".equals(errorType)) {
            log.warn("Unknown tool — cannot recover, aborting");
            return RecoveryDecision.ABORT;
        }

        if ("INTERRUPTED".equals(errorType)) {
            return RecoveryDecision.ABORT;
        }

        // If retries not exhausted and error is transient — retry
        if (retryCount < maxRetriesPerAction && isTransient(errorType)) {
            log.info("Transient failure, will retry ({}/{})", retryCount + 1, maxRetriesPerAction);
            return RecoveryDecision.RETRY;
        }

        // If retries exhausted
        if (retryCount >= maxRetriesPerAction) {
            if (context.task().getReplanCount() < maxReplans) {
                log.info("Retries exhausted, escalating to REPLAN");
                return RecoveryDecision.REPLAN;
            } else {
                log.warn("All budgets exhausted, asking user");
                return RecoveryDecision.ASK_USER;
            }
        }

        // Ask LLM to classify the failure
        try {
            String prompt = buildRecoveryPrompt(context, failedObs, retryCount);
            String response = llmClient.chat(SYSTEM_PROMPT, List.of(LlmMessage.user(prompt)));
            RecoveryDecision decision = parseDecision(response.trim().toUpperCase(), context, retryCount);
            log.info("LLM recovery decision: {}", decision);
            return decision;
        } catch (Exception e) {
            log.warn("LLM recovery decision failed, defaulting to ALTERNATIVE: {}", e.getMessage());
            return RecoveryDecision.ALTERNATIVE;
        }
    }

    private boolean isTransient(String errorType) {
        return "TIMEOUT".equals(errorType)
                || "NETWORK_ERROR".equals(errorType)
                || "TEMPORARY_UNAVAILABLE".equals(errorType);
    }

    private RecoveryDecision parseDecision(String response, TaskContext context, int retryCount) {
        // Validate budget constraints
        if (response.contains("RETRY") && retryCount >= maxRetriesPerAction) {
            return RecoveryDecision.ALTERNATIVE;
        }
        if (response.contains("REPLAN") && context.task().getReplanCount() >= maxReplans) {
            return RecoveryDecision.ASK_USER;
        }

        for (RecoveryDecision d : RecoveryDecision.values()) {
            if (response.contains(d.name())) return d;
        }
        return RecoveryDecision.ALTERNATIVE; // safe default
    }

    private String buildRecoveryPrompt(TaskContext context, Observation failedObs, int retryCount) {
        return """
                Task: %s
                Failed action: %s
                Error type: %s
                Error message: %s
                Retry count so far: %d / %d
                Replan count: %d / %d
                
                What is the best recovery strategy?
                """.formatted(
                context.task().getGoalSpec() != null ? context.task().getGoalSpec().objective() : context.task().getRequest(),
                failedObs.summary(),
                failedObs.toolResult() != null ? failedObs.toolResult().errorType() : "UNKNOWN",
                failedObs.toolResult() != null ? failedObs.toolResult().errorMessage() : "Unknown",
                retryCount, maxRetriesPerAction,
                context.task().getReplanCount(), maxReplans
        );
    }
}
