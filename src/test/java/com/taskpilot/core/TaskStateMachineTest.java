package com.taskpilot.core;

import com.taskpilot.domain.Task;
import com.taskpilot.domain.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateMachineTest {

    private TaskStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new TaskStateMachine();
    }

    private Task newTask() {
        return new Task("test-id", "test request");
    }

    // --- Valid transitions ---

    @Test void created_to_analyzing() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        assertEquals(TaskStatus.ANALYZING, t.getStatus());
    }

    @Test void analyzing_to_running() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, t.getStatus());
    }

    @Test void analyzing_to_failed() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.FAILED);
        assertEquals(TaskStatus.FAILED, t.getStatus());
    }

    @Test void running_to_waiting_approval() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.WAITING_APPROVAL);
        assertEquals(TaskStatus.WAITING_APPROVAL, t.getStatus());
    }

    @Test void running_to_waiting_input() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.WAITING_INPUT);
        assertEquals(TaskStatus.WAITING_INPUT, t.getStatus());
    }

    @Test void running_to_verifying() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.VERIFYING);
        assertEquals(TaskStatus.VERIFYING, t.getStatus());
    }

    @Test void running_to_cancelled() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.CANCELLED);
        assertEquals(TaskStatus.CANCELLED, t.getStatus());
    }

    @Test void waiting_approval_back_to_running() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.WAITING_APPROVAL);
        machine.transition(t, TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, t.getStatus());
    }

    @Test void waiting_input_back_to_running() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.WAITING_INPUT);
        machine.transition(t, TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, t.getStatus());
    }

    @Test void verifying_to_completed() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.VERIFYING);
        machine.transition(t, TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, t.getStatus());
    }

    @Test void verifying_back_to_running() {
        // Verification failed but budget remains — back to loop
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.VERIFYING);
        machine.transition(t, TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, t.getStatus());
    }

    @Test void verifying_to_failed() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.VERIFYING);
        machine.transition(t, TaskStatus.FAILED);
        assertEquals(TaskStatus.FAILED, t.getStatus());
    }

    // --- Invalid transitions ---

    @Test void created_cannot_go_to_running() {
        Task t = newTask();
        assertThrows(IllegalStateException.class,
                () -> machine.transition(t, TaskStatus.RUNNING));
    }

    @Test void created_cannot_go_to_completed() {
        Task t = newTask();
        assertThrows(IllegalStateException.class,
                () -> machine.transition(t, TaskStatus.COMPLETED));
    }

    @Test void running_cannot_go_to_analyzing() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        assertThrows(IllegalStateException.class,
                () -> machine.transition(t, TaskStatus.ANALYZING));
    }

    @Test void analyzing_cannot_go_to_verifying() {
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        assertThrows(IllegalStateException.class,
                () -> machine.transition(t, TaskStatus.VERIFYING));
    }

    // --- Terminal states ---

    @Test void completed_is_terminal() {
        assertTrue(machine.isTerminal(TaskStatus.COMPLETED));
        Task t = newTask();
        machine.transition(t, TaskStatus.ANALYZING);
        machine.transition(t, TaskStatus.RUNNING);
        machine.transition(t, TaskStatus.VERIFYING);
        machine.transition(t, TaskStatus.COMPLETED);
        assertThrows(IllegalStateException.class,
                () -> machine.transition(t, TaskStatus.FAILED));
    }

    @Test void failed_is_terminal() {
        assertTrue(machine.isTerminal(TaskStatus.FAILED));
    }

    @Test void cancelled_is_terminal() {
        assertTrue(machine.isTerminal(TaskStatus.CANCELLED));
    }

    @Test void created_is_not_terminal() {
        assertFalse(machine.isTerminal(TaskStatus.CREATED));
    }

    // --- Status is updated on task ---

    @Test void transition_updates_task_status() {
        Task t = newTask();
        assertEquals(TaskStatus.CREATED, t.getStatus());
        machine.transition(t, TaskStatus.ANALYZING);
        assertEquals(TaskStatus.ANALYZING, t.getStatus());
        assertNotNull(t.getUpdatedAt());
    }
}
