package com.taskpilot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Initializing database schema...");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                request TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'CREATED',
                goal_spec_json TEXT,
                plan_json TEXT,
                facts_json TEXT,
                step_count INTEGER DEFAULT 0,
                replan_count INTEGER DEFAULT 0,
                verify_attempts INTEGER DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                step_no INTEGER NOT NULL,
                action_json TEXT NOT NULL,
                observation_json TEXT,
                recovery_decision TEXT,
                timestamp TEXT NOT NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id)
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS approvals (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                action_digest TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                edited_payload_json TEXT,
                created_at TEXT NOT NULL,
                resolved_at TEXT,
                FOREIGN KEY (task_id) REFERENCES tasks(id)
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS evidence (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                type TEXT NOT NULL,
                data TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id)
            )
        """);
        log.info("Database schema initialization complete.");

        int updated = jdbcTemplate.update(
            "UPDATE tasks SET status = 'FAILED' WHERE status IN ('RUNNING', 'ANALYZING', 'VERIFYING')"
        );
        if (updated > 0) {
            log.warn("Swept {} stale tasks from previous execution to FAILED status.", updated);
        }
    }
}
