package com.taskpilot.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.Evidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Evidence evidence) {
        String sql = """
            INSERT INTO evidence (id, task_id, type, data, created_at)
            VALUES (?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                evidence.id(),
                evidence.taskId(),
                evidence.type(),
                evidence.data(),
                evidence.createdAt().toString());
    }

    public List<Evidence> findByTaskId(String taskId) {
        String sql = "SELECT * FROM evidence WHERE task_id = ? ORDER BY created_at ASC";
        return jdbcTemplate.query(sql, this::mapRowToEvidence, taskId);
    }

    private Evidence mapRowToEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new Evidence(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("type"),
                rs.getString("data"),
                Instant.parse(rs.getString("created_at"))
        );
    }
}
