package com.taskpilot.core;

import com.taskpilot.domain.Task;
import com.taskpilot.domain.TaskStatus;
import com.taskpilot.persistence.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates, retrieves, and manages Task lifecycle.
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final TaskRepository taskRepository;
    private final TaskStateMachine stateMachine;

    public TaskManager(TaskRepository taskRepository, TaskStateMachine stateMachine) {
        this.taskRepository = taskRepository;
        this.stateMachine = stateMachine;
    }

    /** Create a new task in CREATED status. */
    public Task create(String request) {
        String id = UUID.randomUUID().toString();
        Task task = new Task(id, request);
        taskRepository.save(task);
        log.info("Created task {}: {}", id, request);
        return task;
    }

    public Optional<Task> get(String id) {
        return taskRepository.findById(id);
    }

    /** Cancel a task. Only valid if not already terminal. */
    public void cancel(String id) {
        taskRepository.findById(id).ifPresent(task -> {
            if (!stateMachine.isTerminal(task.getStatus())) {
                stateMachine.transition(task, TaskStatus.CANCELLED);
                taskRepository.update(task);
                log.info("Cancelled task {}", id);
            } else {
                log.warn("Cannot cancel terminal task {} (status={})", id, task.getStatus());
            }
        });
    }

    /**
     * Find tasks that were interrupted mid-execution and can be resumed.
     * These are tasks stuck in RUNNING or WAITING_* states.
     */
    public List<Task> findResumable() {
        List<Task> resumable = taskRepository.findByStatus(TaskStatus.RUNNING);
        resumable.addAll(taskRepository.findByStatus(TaskStatus.WAITING_APPROVAL));
        resumable.addAll(taskRepository.findByStatus(TaskStatus.WAITING_INPUT));
        log.info("Found {} resumable tasks", resumable.size());
        return resumable;
    }
}
