package com.taskpilot.domain;

import java.util.Map;

public record PlannedAction(
        String tool,
        Map<String, Object> args,
        String rationale,
        String expectedOutcome,
        boolean finish
) {
    public static PlannedAction finish(String summary) {
        return new PlannedAction("finish", Map.of("summary", summary), summary, "Task completed", true);
    }

    public static PlannedAction action(String tool, Map<String, Object> args, String rationale, String expectedOutcome) {
        return new PlannedAction(tool, args, rationale, expectedOutcome, false);
    }
}
