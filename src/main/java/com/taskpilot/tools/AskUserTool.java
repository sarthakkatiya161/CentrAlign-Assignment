package com.taskpilot.tools;

import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AskUserTool: pauses the agent loop to request clarification or missing info from the user.
 * The answer is provided via POST /api/tasks/{id}/input and injected as a fact.
 * SideEffect: NONE (it's just a question)
 */
@Component
public class AskUserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    // In-memory store for pending questions — keyed by taskId
    // The API layer writes answers here; the orchestrator reads from task facts.
    private final Map<String, String> pendingQuestions = new ConcurrentHashMap<>();

    @Override public String name() { return "ask_user"; }

    @Override public String description() {
        return "Request clarification or missing information from the human user. " +
               "Use this when: a required form field has no data in the profile, " +
               "a situation is ambiguous and guessing would be wrong, " +
               "or human judgment is needed. NEVER invent data — ask instead.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "question", Map.of("type", "string", "description", "The question to ask the user"),
                "context", Map.of("type", "string", "description", "Why this information is needed")
            ),
            "required", Map.of("question", true)
        );
    }

    @Override public SideEffect sideEffect() { return SideEffect.NONE; }
    @Override public SideEffect sideEffectFor(Map<String, Object> args) { return SideEffect.NONE; }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String question = (String) input.getOrDefault("question", "Can you provide more information?");
        String context = (String) input.getOrDefault("context", "");
        log.info("AskUserTool: question='{}', context='{}'", question, context);
        // The orchestrator transitions to WAITING_INPUT after this tool executes
        // and the question is presented to the user via SSE + UI
        return ToolResult.success(Map.of(
            "question", question,
            "context", context,
            "waiting_for_input", true
        ));
    }
}
