package com.taskpilot.domain;

public enum TaskStatus {
    CREATED,
    ANALYZING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_INPUT,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}
