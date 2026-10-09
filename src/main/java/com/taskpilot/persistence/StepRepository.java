package com.taskpilot.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.Observation;
import com.taskpilot.domain.PlannedAction;
import com.taskpilot.domain.RecoveryDecision;
import com.taskpilot.domain.StepRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class StepRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StepRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String taskId, StepRecord step) {
        String sql = """
            INSERT INTO steps (task_id, step_no, action_json, observation_json, recovery_decision, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                taskId,
                step.stepNo(),
                toJson(step.action()),
                toJson(step.observation()),
                step.recoveryDecision() != null ? step.recoveryDecision().name() : null,
                step.timestamp().toString());
    }

    public List<StepRecord> findByTaskId(String taskId) {
        String sql = "SELECT * FROM steps WHERE task_id = ? ORDER BY step_no ASC, id ASC";
        return jdbcTemplate.query(sql, this::mapRowToStepRecord, taskId);
    }

    private StepRecord mapRowToStepRecord(ResultSet rs, int rowNum) throws SQLException {
        PlannedAction action = fromJson(rs.getString("action_json"), PlannedAction.class);
        Observation obs = fromJson(rs.getString("observation_json"), Observation.class);
        String recoveryStr = rs.getString("recovery_decision");
        RecoveryDecision recovery = recoveryStr != null ? RecoveryDecision.valueOf(recoveryStr) : null;
        
        return new StepRecord(
                rs.getInt("step_no"),
                action,
                obs,
                recovery,
                Instant.parse(rs.getString("timestamp"))
        );
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize", e);
        }
    }
}
