package com.taskpilot.domain;

import java.util.List;
import java.util.Map;

public record ToolResult(
        boolean ok,
        Map<String, Object> data,
        String errorType,
        String errorMessage,
        List<String> artifacts,
        long durationMs
) {
    public static ToolResult success(Map<String, Object> data) {
        return new ToolResult(true, data, null, null, List.of(), 0);
    }

    public static ToolResult success(Map<String, Object> data, List<String> artifacts) {
        return new ToolResult(true, data, null, null, artifacts != null ? artifacts : List.of(), 0);
    }

    public static ToolResult failure(String errorType, String errorMessage) {
        return new ToolResult(false, null, errorType, errorMessage, List.of(), 0);
    }

    public static ToolResult timeout() {
        return new ToolResult(false, null, "TIMEOUT", "Execution timed out", List.of(), 0);
    }
}
