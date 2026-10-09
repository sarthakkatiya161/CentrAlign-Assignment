package com.taskpilot.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.GoalSpec;
import com.taskpilot.domain.Task;
import com.taskpilot.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(Task task) {
        String sql = """
            INSERT INTO tasks (id, request, status, goal_spec_json, plan_json, facts_json,
                               step_count, replan_count, verify_attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                task.getId(),
                task.getRequest(),
                task.getStatus().name(),
                toJson(task.getGoalSpec()),
                toJson(task.getCurrentPlan()),
                toJson(task.getFacts()),
                task.getStepCount(),
                task.getReplanCount(),
                task.getVerifyAttempts(),
                task.getCreatedAt().toString(),
                task.getUpdatedAt().toString());
    }

    public void update(Task task) {
        String sql = """
            UPDATE tasks SET status = ?, goal_spec_json = ?, plan_json = ?, facts_json = ?,
                             step_count = ?, replan_count = ?, verify_attempts = ?, updated_at = ?
            WHERE id = ?
        """;
        jdbcTemplate.update(sql,
                task.getStatus().name(),
                toJson(task.getGoalSpec()),
                toJson(task.getCurrentPlan()),
                toJson(task.getFacts()),
                task.getStepCount(),
                task.getReplanCount(),
                task.getVerifyAttempts(),
                task.getUpdatedAt().toString(),
                task.getId());
    }

    public Optional<Task> findById(String id) {
        String sql = "SELECT * FROM tasks WHERE id = ?";
        List<Task> results = jdbcTemplate.query(sql, this::mapRowToTask, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void updateStatus(String id, TaskStatus status) {
        String sql = "UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, status.name(), Instant.now().toString(), id);
    }

    public List<Task> findByStatus(TaskStatus status) {
        String sql = "SELECT * FROM tasks WHERE status = ?";
        return jdbcTemplate.query(sql, this::mapRowToTask, status.name());
    }

    public List<Task> findAll() {
        String sql = "SELECT * FROM tasks ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, this::mapRowToTask);
    }

    private Task mapRowToTask(ResultSet rs, int rowNum) throws SQLException {
        String goalSpecJson = rs.getString("goal_spec_json");
        String planJson = rs.getString("plan_json");
        String factsJson = rs.getString("facts_json");

        GoalSpec goalSpec = goalSpecJson != null ? fromJson(goalSpecJson, GoalSpec.class) : null;
        List<String> plan = planJson != null
                ? fromJson(planJson, new TypeReference<List<String>>() {}) : List.of();
        Map<String, String> facts = factsJson != null
                ? fromJson(factsJson, new TypeReference<Map<String, String>>() {}) : Map.of();

        return new Task(
                rs.getString("id"),
                rs.getString("request"),
                TaskStatus.valueOf(rs.getString("status")),
                goalSpec,
                plan,
                facts,
                rs.getInt("step_count"),
                rs.getInt("replan_count"),
                rs.getInt("verify_attempts"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }
}
