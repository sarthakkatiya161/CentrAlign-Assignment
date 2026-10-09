package com.taskpilot.domain;

import java.util.List;

/**
 * Context passed to the Planner for each iteration.
 * Contains everything the LLM needs to decide the next action.
 */
public record TaskContext(
        Task task,
        Observation lastObservation,
        String toolMenu,
        List<StepRecord> recentHistory
) {}
