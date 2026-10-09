package com.taskpilot.domain;

import java.time.Instant;

public record Evidence(
        String id,
        String taskId,
        String type,
        String data,
        Instant createdAt
) {
    /** Evidence types */
    public static final String SCREENSHOT = "SCREENSHOT";
    public static final String JSON_SNAPSHOT = "JSON_SNAPSHOT";
    public static final String RECORD_ID = "RECORD_ID";
    public static final String MESSAGE_ID = "MESSAGE_ID";
    public static final String PAGE_CONTENT = "PAGE_CONTENT";
    public static final String VERIFICATION = "VERIFICATION";
}
