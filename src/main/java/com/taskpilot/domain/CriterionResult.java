package com.taskpilot.domain;

public record CriterionResult(
        String description,
        boolean passed,
        String evidenceRef,
        String detail
) {}
