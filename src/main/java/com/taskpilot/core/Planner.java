package com.taskpilot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.PlannedAction;
import com.taskpilot.domain.StepRecord;
import com.taskpilot.domain.TaskContext;
import com.taskpilot.llm.LlmClient;
import com.taskpilot.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decides the SINGLE next action for each iteration of the agent loop.
 * The planner has ZERO task-specific knowledge — it only knows the goal,
 * current state, history, and available tools.
 */
@Component
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private static final String SYSTEM_PROMPT = """
            You are an autonomous task planner. Your job is to decide the SINGLE best next action to make progress toward a goal.
            
            Rules:
            - Choose exactly ONE action per turn from the available tools.
            - ACT AUTONOMOUSLY: Explore available tools FIRST (e.g. search email or check http://localhost:3001) instead of asking the user for information you can discover yourself.
            - Only use "ask_user" if you hit a true blocker (e.g. a required form field has no value anywhere in profile or documents after searching).
            - NEVER invent sensitive personal data (GPA, SSN, personal phone) — if truly missing from candidate profile, ask.
            - Prefer verification before claiming done.
            - Adapt to unexpected situations using the information in the observation history.
            - When the goal is fully accomplished and verified, return {"finish": true, "summary": "brief description of what was accomplished"}.
            
            Respond ONLY with valid JSON in ONE of these two formats:
            
            Next action:
            {"tool": "tool_name", "args": {"key": "value"}, "rationale": "why this action", "expectedOutcome": "what should happen"}
            
            Task complete:
            {"finish": true, "summary": "what was accomplished and verified"}
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public Planner(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public PlannedAction next(TaskContext context) {
        String userContent = buildPromptContent(context);
        log.debug("Planning next action for task {}, step {}", context.task().getId(), context.task().getStepCount());

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = llmClient.chatStructured(SYSTEM_PROMPT,
                List.of(LlmMessage.user(userContent)), Map.class);

        if (Boolean.TRUE.equals(raw.get("finish"))) {
            String summary = (String) raw.getOrDefault("summary", "Task completed");
            log.info("Planner declared task complete: {}", summary);
            return PlannedAction.finish(summary);
        }

        String tool = (String) raw.get("tool");
        Object argsObj = raw.get("args");
        String rationale = (String) raw.getOrDefault("rationale", "");
        String expectedOutcome = (String) raw.getOrDefault("expectedOutcome", "");

        @SuppressWarnings("unchecked")
        Map<String, Object> args = argsObj instanceof Map ? (Map<String, Object>) argsObj : Map.of();

        if (tool == null || tool.isBlank()) {
            throw new RuntimeException("Planner returned null/blank tool name");
        }

        log.info("Planned action: tool={}, rationale={}", tool, rationale);
        return PlannedAction.action(tool, args, rationale, expectedOutcome);
    }

    private String buildPromptContent(TaskContext context) {
        var task = context.task();
        var goalSpec = task.getGoalSpec();

        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n");
        sb.append("Objective: ").append(goalSpec != null ? goalSpec.objective() : task.getRequest()).append("\n\n");

        if (goalSpec != null && !goalSpec.constraints().isEmpty()) {
            sb.append("Constraints:\n");
            goalSpec.constraints().forEach(c -> sb.append("- ").append(c).append("\n"));
            sb.append("\n");
        }

        if (goalSpec != null && !goalSpec.successCriteria().isEmpty()) {
            sb.append("Success criteria (must verify all before declaring done):\n");
            goalSpec.successCriteria().forEach(sc ->
                sb.append("- ").append(sc.description()).append("\n"));
            sb.append("\n");
        }

        if (!task.getFacts().isEmpty()) {
            sb.append("## Discovered facts\n");
            task.getFacts().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }

        if (!context.recentHistory().isEmpty()) {
            sb.append("## Recent action history (last ").append(context.recentHistory().size()).append(" steps)\n");
            for (StepRecord step : context.recentHistory()) {
                sb.append("Step ").append(step.stepNo()).append(": ");
                sb.append("tool=").append(step.action().tool()).append(", ");
                sb.append("result=").append(step.observation() != null ?
                        (step.observation().failed() ? "FAILED - " + step.observation().summary() : step.observation().summary())
                        : "pending");
                if (step.recoveryDecision() != null) {
                    sb.append(", recovery=").append(step.recoveryDecision());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (context.lastObservation() != null && context.lastObservation().failed()) {
            sb.append("## Last action FAILED\n");
            sb.append("Error: ").append(context.lastObservation().summary()).append("\n\n");
        }

        sb.append("## Available tools\n");
        sb.append(context.toolMenu()).append("\n\n");

        sb.append("## Step count\n");
        sb.append("Steps executed: ").append(task.getStepCount())
          .append(", Replans: ").append(task.getReplanCount()).append("\n\n");

        sb.append("Decide the single best NEXT action. Respond with JSON only.");
        return sb.toString();
    }
}
