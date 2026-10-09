package com.taskpilot.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.ApprovalRequest;
import com.taskpilot.domain.ApprovalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ApprovalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApprovalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(ApprovalRequest request) {
        String sql = """
            INSERT INTO approvals (id, task_id, action_digest, payload_hash, status, edited_payload_json, created_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                request.getId(),
                request.getTaskId(),
                request.getActionDigest(),
                request.getPayloadHash(),
                request.getStatus().name(),
                toJson(request.getEditedPayload()),
                request.getCreatedAt().toString(),
                request.getResolvedAt() != null ? request.getResolvedAt().toString() : null);
    }

    public Optional<ApprovalRequest> findById(String id) {
        String sql = "SELECT * FROM approvals WHERE id = ?";
        List<ApprovalRequest> results = jdbcTemplate.query(sql, this::mapRowToApproval, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ApprovalRequest> findPendingByTaskId(String taskId) {
        String sql = "SELECT * FROM approvals WHERE task_id = ? AND status = 'PENDING' LIMIT 1";
        List<ApprovalRequest> results = jdbcTemplate.query(sql, this::mapRowToApproval, taskId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find an already-approved request for a specific payload hash.
     * Used by PolicyGate to verify approval before allowing EXTERNAL actions.
     */
    public Optional<ApprovalRequest> findApprovedByTaskIdAndHash(String taskId, String payloadHash) {
        String sql = "SELECT * FROM approvals WHERE task_id = ? AND payload_hash = ? AND status IN ('APPROVED', 'EDITED') LIMIT 1";
        List<ApprovalRequest> results = jdbcTemplate.query(sql, this::mapRowToApproval, taskId, payloadHash);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ApprovalRequest> findByTaskId(String taskId) {
        String sql = "SELECT * FROM approvals WHERE task_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, this::mapRowToApproval, taskId);
    }

    public void updateStatus(String id, ApprovalStatus status, Map<String, Object> editedPayload) {
        String sql = "UPDATE approvals SET status = ?, edited_payload_json = ?, resolved_at = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                status.name(),
                toJson(editedPayload),
                Instant.now().toString(),
                id);
    }

    private ApprovalRequest mapRowToApproval(ResultSet rs, int rowNum) throws SQLException {
        ApprovalRequest req = new ApprovalRequest();
        req.setId(rs.getString("id"));
        req.setTaskId(rs.getString("task_id"));
        req.setActionDigest(rs.getString("action_digest"));
        req.setPayloadHash(rs.getString("payload_hash"));
        req.setStatus(ApprovalStatus.valueOf(rs.getString("status")));
        req.setEditedPayload(fromJson(rs.getString("edited_payload_json"), new TypeReference<Map<String, Object>>() {}));
        req.setCreatedAt(Instant.parse(rs.getString("created_at")));
        String resolvedStr = rs.getString("resolved_at");
        if (resolvedStr != null) req.setResolvedAt(Instant.parse(resolvedStr));
        return req;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize", e);
        }
    }
}
