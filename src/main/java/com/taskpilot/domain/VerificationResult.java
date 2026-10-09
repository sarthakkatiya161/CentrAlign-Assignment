package com.taskpilot.domain;

import java.util.List;

public record VerificationResult(
        List<CriterionResult> criteriaResults
) {
    public boolean allPassed() {
        return criteriaResults != null
                && !criteriaResults.isEmpty()
                && criteriaResults.stream().allMatch(CriterionResult::passed);
    }
}
