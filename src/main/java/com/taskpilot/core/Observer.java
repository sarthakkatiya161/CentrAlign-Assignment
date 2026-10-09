package com.taskpilot.core;

import com.taskpilot.domain.Observation;
import com.taskpilot.domain.PlannedAction;
import com.taskpilot.domain.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Converts a raw ToolResult into a human-readable Observation.
 * Determines whether the step failed and produces a summary.
 */
@Component
public class Observer {

    public Observation record(int stepNo, PlannedAction action, ToolResult result) {
        boolean failed = !result.ok();
        String summary = buildSummary(action, result);
        // actionId is tool+step for traceability
        String actionId = action.tool() + "#" + stepNo;

        return new Observation(stepNo, actionId, summary, result, failed, Instant.now());
    }

    private String buildSummary(PlannedAction action, ToolResult result) {
        if (result.ok()) {
            if (result.data() != null && !result.data().isEmpty()) {
                // Pick a few meaningful keys to include in summary
                var data = result.data();
                if (data.containsKey("results") || data.containsKey("items")) {
                    Object items = data.getOrDefault("results", data.get("items"));
                    return "Tool '%s' succeeded → found %s items".formatted(
                            action.tool(), countItems(items));
                }
                if (data.containsKey("id")) {
                    return "Tool '%s' succeeded → id=%s".formatted(action.tool(), data.get("id"));
                }
                if (data.containsKey("status")) {
                    return "Tool '%s' succeeded → status=%s".formatted(action.tool(), data.get("status"));
                }
                if (data.containsKey("content") || data.containsKey("text")) {
                    Object content = data.getOrDefault("content", data.get("text"));
                    String preview = content != null ? content.toString().substring(
                            0, Math.min(100, content.toString().length())) : "";
                    return "Tool '%s' succeeded → %s...".formatted(action.tool(), preview);
                }
                return "Tool '%s' succeeded".formatted(action.tool());
            }
            return "Tool '%s' succeeded".formatted(action.tool());
        } else {
            return "Tool '%s' FAILED: [%s] %s".formatted(
                    action.tool(),
                    result.errorType() != null ? result.errorType() : "ERROR",
                    result.errorMessage() != null ? result.errorMessage() : "Unknown error");
        }
    }

    private Object countItems(Object items) {
        if (items instanceof java.util.Collection<?> c) return c.size();
        if (items instanceof Object[] a) return a.length;
        return "some";
    }
}
