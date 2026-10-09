package com.taskpilot.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a pending human approval for an EXTERNAL side-effect action.
 * The approval is bound to a specific payload hash — if the payload changes,
 * the approval is invalidated.
 */
public class ApprovalRequest {

    private String id;
    private String taskId;
    private String actionDigest;   // human-readable tool:args description
    private String payloadHash;    // SHA-256 of canonical JSON args
    private ApprovalStatus status;
    private Map<String, Object> editedPayload;
    private Instant createdAt;
    private Instant resolvedAt;

    /** Default constructor for repository deserialization. */
    public ApprovalRequest() {}

    public ApprovalRequest(String id, String taskId, String actionDigest, String payloadHash,
                           ApprovalStatus status, Map<String, Object> editedPayload,
                           Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.taskId = taskId;
        this.actionDigest = actionDigest;
        this.payloadHash = payloadHash;
        this.status = status;
        this.editedPayload = editedPayload;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    // Getters
    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getActionDigest() { return actionDigest; }
    public String getPayloadHash() { return payloadHash; }
    public ApprovalStatus getStatus() { return status; }
    public Map<String, Object> getEditedPayload() { return editedPayload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    // Setters (used by repository row mapper)
    public void setId(String id) { this.id = id; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public void setActionDigest(String actionDigest) { this.actionDigest = actionDigest; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public void setStatus(ApprovalStatus status) { this.status = status; }
    public void setEditedPayload(Map<String, Object> editedPayload) { this.editedPayload = editedPayload; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
