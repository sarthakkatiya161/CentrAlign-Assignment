package com.taskpilot.domain;

import java.time.Instant;

public record Observation(
        int stepNo,
        String actionId,
        String summary,
        ToolResult toolResult,
        boolean failed,
        Instant timestamp
) {}
