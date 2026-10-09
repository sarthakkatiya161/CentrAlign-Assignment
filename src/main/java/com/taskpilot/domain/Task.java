package com.taskpilot.domain;

import java.time.Instant;
import java.util.*;

/**
 * Central mutable entity representing a task through its lifecycle.
 * Persisted after each step for restart tolerance.
 */
public class Task {

    private final String id;
    private final String request;
    private TaskStatus status;
    private GoalSpec goalSpec;
    private List<String> currentPlan;
    private Map<String, String> facts;
    private final List<StepRecord> steps;
    private int stepCount;
    private int replanCount;
    private int verifyAttempts;
    private final Instant createdAt;
    private Instant updatedAt;

    public Task(String id, String request) {
        this.id = id;
        this.request = request;
        this.status = TaskStatus.CREATED;
        this.currentPlan = new ArrayList<>();
        this.facts = new LinkedHashMap<>();
        this.steps = new ArrayList<>();
        this.stepCount = 0;
        this.replanCount = 0;
        this.verifyAttempts = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Full-arg constructor for deserialization from DB. */
    public Task(String id, String request, TaskStatus status, GoalSpec goalSpec,
                List<String> currentPlan, Map<String, String> facts,
                int stepCount, int replanCount, int verifyAttempts,
                Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.request = request;
        this.status = status;
        this.goalSpec = goalSpec;
        this.currentPlan = currentPlan != null ? new ArrayList<>(currentPlan) : new ArrayList<>();
        this.facts = facts != null ? new LinkedHashMap<>(facts) : new LinkedHashMap<>();
        this.steps = new ArrayList<>();
        this.stepCount = stepCount;
        this.replanCount = replanCount;
        this.verifyAttempts = verifyAttempts;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getRequest() { return request; }
    public TaskStatus getStatus() { return status; }
    public GoalSpec getGoalSpec() { return goalSpec; }
    public List<String> getCurrentPlan() { return Collections.unmodifiableList(currentPlan); }
    public Map<String, String> getFacts() { return facts; }
    public List<StepRecord> getSteps() { return Collections.unmodifiableList(steps); }
    public int getStepCount() { return stepCount; }
    public int getReplanCount() { return replanCount; }
    public int getVerifyAttempts() { return verifyAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // --- Mutators ---

    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void setGoalSpec(GoalSpec goalSpec) {
        this.goalSpec = goalSpec;
        this.updatedAt = Instant.now();
    }

    public void setCurrentPlan(List<String> plan) {
        this.currentPlan = plan != null ? new ArrayList<>(plan) : new ArrayList<>();
        this.updatedAt = Instant.now();
    }

    public void addStep(StepRecord step) {
        this.steps.add(step);
        this.updatedAt = Instant.now();
    }

    public void incrementStepCount() {
        this.stepCount++;
        this.updatedAt = Instant.now();
    }

    public void incrementReplanCount() {
        this.replanCount++;
        this.updatedAt = Instant.now();
    }

    public void incrementVerifyAttempts() {
        this.verifyAttempts++;
        this.updatedAt = Instant.now();
    }

    public void addFact(String key, String value) {
        this.facts.put(key, value);
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', status=" + status + ", steps=" + stepCount + "}";
    }
}
