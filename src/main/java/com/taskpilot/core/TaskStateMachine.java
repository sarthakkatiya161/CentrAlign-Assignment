package com.taskpilot.core;

import com.taskpilot.domain.Task;
import com.taskpilot.domain.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces legal task state transitions.
 * All status changes MUST go through this class.
 * Illegal transitions throw IllegalStateException immediately.
 */
@Component
public class TaskStateMachine {

    // Maps each status to its set of valid next statuses
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            TaskStatus.CREATED,          EnumSet.of(TaskStatus.ANALYZING),
            TaskStatus.ANALYZING,        EnumSet.of(TaskStatus.RUNNING, TaskStatus.FAILED),
            TaskStatus.RUNNING,          EnumSet.of(TaskStatus.WAITING_APPROVAL, TaskStatus.WAITING_INPUT,
                                                    TaskStatus.VERIFYING, TaskStatus.FAILED, TaskStatus.CANCELLED),
            TaskStatus.WAITING_APPROVAL, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.WAITING_INPUT,    EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.VERIFYING,        EnumSet.of(TaskStatus.COMPLETED, TaskStatus.RUNNING, TaskStatus.FAILED),
            TaskStatus.COMPLETED,        EnumSet.noneOf(TaskStatus.class),  // terminal
            TaskStatus.FAILED,           EnumSet.noneOf(TaskStatus.class),  // terminal
            TaskStatus.CANCELLED,        EnumSet.noneOf(TaskStatus.class)   // terminal
    );

    /**
     * Transition task to newStatus. Updates task.status and task.updatedAt.
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transition(Task task, TaskStatus newStatus) {
        TaskStatus current = task.getStatus();
        if (!canTransition(current, newStatus)) {
            throw new IllegalStateException(
                    "Illegal state transition: " + current + " → " + newStatus
                    + " for task " + task.getId());
        }
        task.setStatus(newStatus);
    }

    /**
     * Returns true if a transition from → to is legal.
     */
    public boolean canTransition(TaskStatus from, TaskStatus to) {
        Set<TaskStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Returns true if the status is terminal (no further transitions possible).
     */
    public boolean isTerminal(TaskStatus status) {
        Set<TaskStatus> allowed = TRANSITIONS.get(status);
        return allowed != null && allowed.isEmpty();
    }
}
