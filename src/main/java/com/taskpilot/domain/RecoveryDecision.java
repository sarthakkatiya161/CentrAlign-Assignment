package com.taskpilot.domain;

public enum RecoveryDecision {
    RETRY,
    ALTERNATIVE,
    REPLAN,
    ASK_USER,
    ABORT
}
