package com.taskpilot.domain;

public record SuccessCriterion(
        String description,
        String verificationMethod,
        String evidenceType
) {}
