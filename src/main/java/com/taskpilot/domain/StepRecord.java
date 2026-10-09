package com.taskpilot.domain;

import java.time.Instant;

/**
 * A record of one step in the agent loop: the planned action, the observation
 * after execution, and any recovery decision made.
 */
public record StepRecord(
        int stepNo,
        PlannedAction action,
        Observation observation,
        RecoveryDecision recoveryDecision,
        Instant timestamp
) {
    /** Return a copy with the recovery decision set. */
    public StepRecord withRecovery(RecoveryDecision decision) {
        return new StepRecord(stepNo, action, observation, decision, timestamp);
    }
}
