package com.taskpilot.domain;

/**
 * Result of the PolicyGate check on an action.
 * Determines whether the action can proceed, needs approval, or is blocked.
 */
public record PolicyDecision(
        boolean allowed,
        String reason,
        boolean requiresApproval,
        ApprovalRequest approvalRequest
) {
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null, false, null);
    }

    public static PolicyDecision allowWithLog(String reason) {
        return new PolicyDecision(true, reason, false, null);
    }

    public static PolicyDecision needsApproval(ApprovalRequest request) {
        return new PolicyDecision(false, "Requires human approval", true, request);
    }

    public static PolicyDecision blocked(String reason) {
        return new PolicyDecision(false, reason, false, null);
    }
}
