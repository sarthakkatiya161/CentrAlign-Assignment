package com.taskpilot.domain;

import java.util.List;

public record TaskResult(
        String taskId,
        TaskStatus status,
        String summary,
        List<String> evidenceRefs,
        int humanInterventions,
        int stepsExecuted,
        int replans,
        long durationMs
) {}
