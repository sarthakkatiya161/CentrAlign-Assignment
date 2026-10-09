package com.taskpilot.core;

import com.taskpilot.domain.GoalSpec;
import com.taskpilot.domain.SuccessCriterion;
import com.taskpilot.llm.LlmClient;
import com.taskpilot.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Analyzes a natural-language goal and extracts:
 * - Objective
 * - Constraints
 * - Checkable success criteria (with verification methods)
 * - Clarifying questions (if info is missing)
 */
@Component
public class GoalAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GoalAnalyzer.class);

    private static final String SYSTEM_PROMPT = """
            You are a goal analyzer for an autonomous task worker.
            
            Given a user's task request and a list of available tools, extract:
            1. objective: What needs to be accomplished (concise sentence)
            2. constraints: Any limitations, requirements, or rules (list of strings)
            3. successCriteria: Specific, independently checkable conditions that prove the task is done.
               Each criterion needs:
               - description: what to check
               - verificationMethod: one of "api_check", "email_check", "file_check", "page_check", "llm_judge"
               - evidenceType: one of "RECORD_ID", "MESSAGE_ID", "JSON_SNAPSHOT", "PAGE_CONTENT", "SCREENSHOT"
            4. clarifyingQuestions: Questions to ask the user if critical information is missing (empty if not needed)
            
            IMPORTANT: Be specific about success criteria. "Application submitted" is too vague.
            "GET /api/applications returns a record with name=X and status=submitted" is a valid criterion.
            
            Respond ONLY with valid JSON matching this structure:
            {
              "objective": "...",
              "constraints": ["..."],
              "successCriteria": [
                {"description": "...", "verificationMethod": "api_check", "evidenceType": "RECORD_ID"}
              ],
              "clarifyingQuestions": []
            }
            """;

    private final LlmClient llmClient;

    public GoalAnalyzer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public GoalSpec analyze(String userRequest, String toolMenu) {
        log.info("Analyzing goal: {}", userRequest);

        String userContent = """
                Task request: %s
                
                Available tools:
                %s
                
                Extract the goal specification as JSON.
                """.formatted(userRequest, toolMenu);

        try {
            GoalSpec spec = llmClient.chatStructured(
                    SYSTEM_PROMPT,
                    List.of(LlmMessage.user(userContent)),
                    GoalSpec.class
            );
            log.info("Goal analyzed: objective={}, criteria={}", spec.objective(), spec.successCriteria().size());
            return spec;
        } catch (Exception e) {
            log.warn("Failed to parse GoalSpec from LLM, using fallback. Error: {}", e.getMessage());
            // Fallback: minimal GoalSpec so the agent can still proceed
            return new GoalSpec(
                    userRequest,
                    List.of(),
                    List.of(new SuccessCriterion(
                            "Task completed as requested by user",
                            "llm_judge",
                            "PAGE_CONTENT"
                    )),
                    List.of()
            );
        }
    }
}
