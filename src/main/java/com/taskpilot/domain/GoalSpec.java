package com.taskpilot.domain;

import java.util.List;

public record GoalSpec(
        String objective,
        List<String> constraints,
        List<SuccessCriterion> successCriteria,
        List<String> clarifyingQuestions
) {}
