package com.taskpilot.core;

import org.springframework.context.ApplicationEvent;

/** SSE/event bus event emitted at each agent loop step. */
public class TaskEvent extends ApplicationEvent {
    private final String taskId;
    private final String type;
    private final String message;

    public TaskEvent(Object source, String taskId, String type, String message) {
        super(source);
        this.taskId = taskId;
        this.type = type;
        this.message = message;
    }

    public String getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
}
