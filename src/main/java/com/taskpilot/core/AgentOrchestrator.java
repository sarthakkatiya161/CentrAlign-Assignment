package com.taskpilot.core;

import com.taskpilot.domain.*;
import com.taskpilot.persistence.ApprovalRepository;
import com.taskpilot.persistence.StepRepository;
import com.taskpilot.persistence.TaskRepository;
import com.taskpilot.tools.Tool;
import com.taskpilot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * THE MAIN AGENT LOOP.
 *
 * Zero task-specific code. This class knows only about:
 * - Goals, plans, actions, observations, tools
 * - Approval gates and budgets (Java-enforced)
 * - Verification and evidence
 *
 * Adding a new task type requires ZERO changes here.
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final TaskManager taskManager;
    private final TaskStateMachine stateMachine;
    private final GoalAnalyzer goalAnalyzer;
    private final Planner planner;
    private final PolicyGate policyGate;
    private final Executor executor;
    private final Observer observer;
    private final RecoveryManager recoveryManager;
    private final Verifier verifier;
    private final EvidenceService evidenceService;
    private final ToolRegistry toolRegistry;
    private final TaskRepository taskRepository;
    private final StepRepository stepRepository;
    private final ApprovalRepository approvalRepository;
    private final ApplicationEventPublisher eventPublisher;

    // Hard budget limits — enforced in Java, not by the LLM
    private final int maxSteps;
    private final int maxReplans;
    private final int maxRetriesPerAction;
    private final int maxVerifyCycles;

    public AgentOrchestrator(
            TaskManager taskManager, TaskStateMachine stateMachine,
            GoalAnalyzer goalAnalyzer, Planner planner,
            PolicyGate policyGate, Executor executor, Observer observer,
            RecoveryManager recoveryManager, Verifier verifier,
            EvidenceService evidenceService, ToolRegistry toolRegistry,
            TaskRepository taskRepository, StepRepository stepRepository,
            ApprovalRepository approvalRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${taskpilot.agent.max-steps:40}") int maxSteps,
            @Value("${taskpilot.agent.max-replans:3}") int maxReplans,
            @Value("${taskpilot.agent.max-retries-per-action:2}") int maxRetriesPerAction,
            @Value("${taskpilot.agent.max-verify-cycles:2}") int maxVerifyCycles) {
        this.taskManager = taskManager;
        this.stateMachine = stateMachine;
        this.goalAnalyzer = goalAnalyzer;
        this.planner = planner;
        this.policyGate = policyGate;
        this.executor = executor;
        this.observer = observer;
        this.recoveryManager = recoveryManager;
        this.verifier = verifier;
        this.evidenceService = evidenceService;
        this.toolRegistry = toolRegistry;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.approvalRepository = approvalRepository;
        this.eventPublisher = eventPublisher;
        this.maxSteps = maxSteps;
        this.maxReplans = maxReplans;
        this.maxRetriesPerAction = maxRetriesPerAction;
        this.maxVerifyCycles = maxVerifyCycles;
    }

    /**
     * Execute a task asynchronously from the start.
     * Returns CompletableFuture<TaskResult>.
     * Pauses if approval/input is needed (task remains persisted).
     */
    @Async("agentExecutor")
    public CompletableFuture<TaskResult> executeTask(String taskId) {
        MDC.put("taskId", taskId);
        try {
            Task task = taskManager.get(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            TaskResult result = runLoop(task);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Unexpected error executing task {}", taskId, e);
            taskManager.get(taskId).ifPresent(t -> {
                if (!stateMachine.isTerminal(t.getStatus())) {
                    try {
                        stateMachine.transition(t, TaskStatus.FAILED);
                        taskRepository.update(t);
                        emit(t, "FAILED", "Task failed due to unexpected error: " + e.getMessage());
                    } catch (Exception ex) {
                        log.error("Failed to mark task as FAILED", ex);
                    }
                }
            });
            return CompletableFuture.failedFuture(e);
        } finally {
            MDC.remove("taskId");
        }
    }

    /** Resume after human approval was granted. */
    @Async("agentExecutor")
    public CompletableFuture<TaskResult> resumeAfterApproval(String taskId) {
        MDC.put("taskId", taskId);
        try {
            Task task = taskManager.get(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            if (task.getStatus() != TaskStatus.WAITING_APPROVAL) {
                log.warn("resumeAfterApproval called on task {} in status {}", taskId, task.getStatus());
                return CompletableFuture.completedFuture(buildResult(task));
            }
            stateMachine.transition(task, TaskStatus.RUNNING);
            taskRepository.update(task);
            TaskResult result = runLoop(task);
            return CompletableFuture.completedFuture(result);
        } finally {
            MDC.remove("taskId");
        }
    }

    /** Resume after user provided clarification input. */
    @Async("agentExecutor")
    public CompletableFuture<TaskResult> resumeAfterInput(String taskId, String answer) {
        MDC.put("taskId", taskId);
        try {
            Task task = taskManager.get(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            if (task.getStatus() != TaskStatus.WAITING_INPUT) {
                log.warn("resumeAfterInput called on task {} in status {}", taskId, task.getStatus());
                return CompletableFuture.completedFuture(buildResult(task));
            }
            // Store the answer as a fact
            task.addFact("user_answer_" + task.getStepCount(), answer);
            task.addFact("last_user_answer", answer);
            stateMachine.transition(task, TaskStatus.RUNNING);
            taskRepository.update(task);
            TaskResult result = runLoop(task);
            return CompletableFuture.completedFuture(result);
        } finally {
            MDC.remove("taskId");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN AGENT LOOP
    // ─────────────────────────────────────────────────────────────────────────

    private TaskResult runLoop(Task task) {
        long startTime = System.currentTimeMillis();
        log.info("Starting agent loop for task {} (steps={}, replans={})",
                task.getId(), task.getStepCount(), task.getReplanCount());

        // ── PHASE 1: ANALYZE GOAL ───────────────────────────────────────────
        if (task.getStatus() == TaskStatus.CREATED || task.getStatus() == TaskStatus.ANALYZING) {
            try {
                stateMachine.transition(task, TaskStatus.ANALYZING);
                taskRepository.update(task);
                emit(task, "ANALYZING", "Analyzing goal...");

                GoalSpec goalSpec = goalAnalyzer.analyze(task.getRequest(), toolRegistry.menuForLLM());
                task.setGoalSpec(goalSpec);

                // Ask clarifying questions if needed
                if (goalSpec.clarifyingQuestions() != null && !goalSpec.clarifyingQuestions().isEmpty()) {
                    String questions = String.join("\n", goalSpec.clarifyingQuestions());
                    task.addFact("pending_clarifications", questions);
                    emit(task, "CLARIFICATION_NEEDED", questions);
                    // Still proceed — the planner will use ask_user if it needs info
                }

                stateMachine.transition(task, TaskStatus.RUNNING);
                taskRepository.update(task);
            } catch (Exception e) {
                log.error("Goal analysis failed: {}", e.getMessage());
                stateMachine.transition(task, TaskStatus.FAILED);
                taskRepository.update(task);
                return buildResult(task);
            }
        }

        // ── PHASE 2: ACTION LOOP ────────────────────────────────────────────
        Map<String, Integer> retryCounts = new HashMap<>(); // actionKey → retry count
        Map<String, Integer> executionCounts = new HashMap<>(); // actionKey -> success count

        while (true) {
            if (task.getStepCount() >= maxSteps) {
                stateMachine.transition(task, TaskStatus.FAILED);
                taskRepository.update(task);
                emit(task, "FAILED", "Max steps (" + maxSteps + ") reached without completing task");
                return buildResult(task);
            }
            if (task.getReplanCount() >= maxReplans) {
                stateMachine.transition(task, TaskStatus.FAILED);
                taskRepository.update(task);
                emit(task, "FAILED", "Max replans (" + maxReplans + ") reached without completing task");
                return buildResult(task);
            }

            // Build context snapshot for this iteration
            TaskContext context = buildContext(task);

            // Plan next action
            emit(task, "PLANNING", "Planning next action...");
            PlannedAction action = planner.next(context);

            // Planner says we're done → go to verification
            if (action.finish()) {
                log.info("Planner declared finish: {}", action.rationale());
                emit(task, "FINISHING", action.rationale());
                break;
            }

            // Look up the tool
            Optional<Tool> toolOpt = toolRegistry.get(action.tool());
            if (toolOpt.isEmpty()) {
                log.warn("Planner chose unknown tool '{}' — treating as failure", action.tool());
                Observation obs = observer.record(task.getStepCount(), action,
                        ToolResult.failure("UNKNOWN_TOOL", "No tool: " + action.tool()));
                recordStep(task, action, obs, RecoveryDecision.ABORT);
                stateMachine.transition(task, TaskStatus.FAILED);
                taskRepository.update(task);
                emit(task, "FAILED", "Unknown tool: " + action.tool());
                return buildResult(task);
            }
            Tool tool = toolOpt.get();

            // ── POLICY GATE (Java-enforced) ──────────────────────────────────
            PolicyDecision policy = policyGate.check(task, action, tool);

            if (policy.requiresApproval()) {
                stateMachine.transition(task, TaskStatus.WAITING_APPROVAL);
                taskRepository.update(task);
                emit(task, "WAITING_APPROVAL", buildApprovalSummary(action, policy));
                log.info("Task {} paused for approval (approvalId={})",
                        task.getId(), policy.approvalRequest().getId());
                return null; // loop will resume via resumeAfterApproval()
            }

            if (!policy.allowed()) {
                log.warn("Action blocked by policy: {}", policy.reason());
                continue; // skip this action, replanner will try something else
            }

            // ── EXECUTE ──────────────────────────────────────────────────────
            String execKey = action.tool() + ":" + action.args().toString();
            int execCount = executionCounts.getOrDefault(execKey, 0) + 1;
            executionCounts.put(execKey, execCount);
            
            if (execCount >= 3 && !"ask_user".equals(action.tool())) {
                log.warn("Action {} repeated {} times. Forcing replan.", execKey, execCount);
                task.incrementReplanCount();
                taskRepository.update(task);
                emit(task, "RECOVERY", "Action repeated too many times. Forcing replan.");
                continue;
            }

            emit(task, "EXECUTING", "Executing: " + action.tool() + " — " + action.rationale());
            ToolResult result = executor.run(action);
            Observation obs = observer.record(task.getStepCount(), action, result);

            // ── HANDLE FAILURE ───────────────────────────────────────────────
            if (obs.failed()) {
                String actionKey = action.tool() + ":" + action.args().toString();
                int retryCount = retryCounts.getOrDefault(actionKey, 0);

                RecoveryDecision recovery = recoveryManager.decide(buildContext(task), obs, retryCount);
                recordStep(task, action, obs, recovery);
                emit(task, "RECOVERY", recovery + ": " + obs.summary());

                switch (recovery) {
                    case RETRY -> {
                        retryCounts.put(actionKey, retryCount + 1);
                        log.info("Retrying action: {}", action.tool());
                        // do NOT increment stepCount — same action retried
                    }
                    case ALTERNATIVE -> {
                        // Planner will pick a different approach next iteration
                        log.info("Trying alternative approach");
                        task.incrementStepCount();
                    }
                    case REPLAN -> {
                        task.incrementStepCount();
                        task.incrementReplanCount();
                        retryCounts.clear();
                        log.info("Replanning (replan {}/{})", task.getReplanCount(), maxReplans);
                    }
                    case ASK_USER -> {
                        task.incrementStepCount();
                        stateMachine.transition(task, TaskStatus.WAITING_INPUT);
                        taskRepository.update(task);
                        emit(task, "WAITING_INPUT", obs.summary());
                        log.info("Task {} waiting for user input", task.getId());
                        return null; // resume via resumeAfterInput()
                    }
                    case ABORT -> {
                        task.incrementStepCount();
                        stateMachine.transition(task, TaskStatus.FAILED);
                        taskRepository.update(task);
                        emit(task, "FAILED", obs.summary());
                        return buildResult(task);
                    }
                }
                taskRepository.update(task);
                continue;
            }

            // ── SUCCESS: record step and extract facts ────────────────────────
            recordStep(task, action, obs, null);
            extractFacts(task, result);
            task.incrementStepCount();

            // Collect evidence artifacts from tool result
            if (result.artifacts() != null) {
                for (String artifact : result.artifacts()) {
                    evidenceService.save(task.getId(), Evidence.SCREENSHOT, artifact);
                }
            }

            taskRepository.update(task);
            emit(task, "OBSERVATION", obs.summary());

            if ("ask_user".equals(action.tool())) {
                stateMachine.transition(task, TaskStatus.WAITING_INPUT);
                taskRepository.update(task);
                emit(task, "WAITING_INPUT", obs.summary());
                log.info("Task {} waiting for user input (from ask_user tool)", task.getId());
                return null; // pause loop
            }
        }

        // ── PHASE 3: VERIFICATION ────────────────────────────────────────────
        if (task.getStatus() == TaskStatus.RUNNING) {
            stateMachine.transition(task, TaskStatus.VERIFYING);
            taskRepository.update(task);
        }

        GoalSpec goalSpec = task.getGoalSpec();
        if (goalSpec == null) {
            // No goal spec — can't verify, mark complete
            stateMachine.transition(task, TaskStatus.COMPLETED);
            taskRepository.update(task);
            return buildResult(task);
        }

        for (int v = 0; v < maxVerifyCycles; v++) {
            emit(task, "VERIFYING", "Verifying task completion (cycle " + (v + 1) + ")...");
            task.incrementVerifyAttempts();

            VerificationResult verResult = verifier.verify(goalSpec, buildContext(task));

            // Save evidence for each criterion
            for (CriterionResult cr : verResult.criteriaResults()) {
                String data = cr.description() + " → " + (cr.passed() ? "PASS" : "FAIL") + ": " + cr.detail();
                evidenceService.save(task.getId(), Evidence.VERIFICATION, data);
            }

            if (verResult.allPassed()) {
                stateMachine.transition(task, TaskStatus.COMPLETED);
                taskRepository.update(task);
                emit(task, "COMPLETED", "All success criteria verified ✓");
                log.info("Task {} completed and verified", task.getId());
                return buildResult(task);
            }

            // Verification failed — feed failure back into the loop
            log.info("Verification failed (cycle {}/{}), feeding back into planner", v + 1, maxVerifyCycles);
            String failSummary = verResult.criteriaResults().stream()
                    .filter(cr -> !cr.passed())
                    .map(cr -> "FAIL: " + cr.description() + " — " + cr.detail())
                    .collect(Collectors.joining("\n"));

            if (v < maxVerifyCycles - 1 && task.getReplanCount() < maxReplans) {
                stateMachine.transition(task, TaskStatus.RUNNING);
                taskRepository.update(task);
                task.addFact("verification_failure_" + (v + 1), failSummary);
                task.incrementReplanCount();
                emit(task, "VERIFY_FAIL", "Verification failed, retrying: " + failSummary);

                // Re-enter the action loop for one more cycle
                // (limited — we don't want infinite loops)
                TaskContext context = buildContext(task);
                PlannedAction fixAction = planner.next(context);
                if (!fixAction.finish()) {
                    Tool tool = toolRegistry.get(fixAction.tool()).orElse(null);
                    if (tool != null) {
                        PolicyDecision policy = policyGate.check(task, fixAction, tool);
                        if (policy.allowed()) {
                            ToolResult fr = executor.run(fixAction);
                            Observation fObs = observer.record(task.getStepCount(), fixAction, fr);
                            recordStep(task, fixAction, fObs, null);
                            if (!fObs.failed()) extractFacts(task, fr);
                            task.incrementStepCount();
                            taskRepository.update(task);
                        }
                    }
                }
                stateMachine.transition(task, TaskStatus.VERIFYING);
                taskRepository.update(task);
            }
        }

        // Verification exhausted
        stateMachine.transition(task, TaskStatus.FAILED);
        taskRepository.update(task);
        emit(task, "FAILED", "Verification failed after " + maxVerifyCycles + " cycles");
        log.warn("Task {} failed verification", task.getId());
        return buildResult(task);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private TaskContext buildContext(Task task) {
        List<StepRecord> history = task.getSteps();
        int fromIdx = Math.max(0, history.size() - 10);
        List<StepRecord> recentHistory = history.subList(fromIdx, history.size());

        Observation lastObs = history.isEmpty() ? null
                : history.get(history.size() - 1).observation();

        return new TaskContext(task, lastObs, toolRegistry.menuForLLM(), recentHistory);
    }

    private void recordStep(Task task, PlannedAction action, Observation obs, RecoveryDecision recovery) {
        StepRecord step = new StepRecord(task.getStepCount(), action, obs, recovery, Instant.now());
        task.addStep(step);
        stepRepository.save(task.getId(), step);
    }

    private void extractFacts(Task task, ToolResult result) {
        if (result.data() == null) return;
        // Extract simple string values as facts
        result.data().forEach((k, v) -> {
            if (v instanceof String s && !s.isBlank()) {
                task.addFact(k, s);
            } else if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                task.addFact(k, v.toString());
            }
        });
    }

    private TaskResult buildResult(Task task) {
        List<Evidence> evidence = evidenceService.getForTask(task.getId());
        List<String> evidenceRefs = evidence.stream().map(Evidence::id).toList();

        String summary = switch (task.getStatus()) {
            case COMPLETED -> "Task completed successfully";
            case FAILED -> "Task failed after " + task.getStepCount() + " steps";
            case CANCELLED -> "Task was cancelled";
            case WAITING_APPROVAL -> "Task is waiting for human approval";
            case WAITING_INPUT -> "Task is waiting for user input";
            default -> "Task status: " + task.getStatus();
        };

        return new TaskResult(
                task.getId(), task.getStatus(), summary,
                evidenceRefs,
                0, // humanInterventions — tracked via approval/input counts
                task.getStepCount(),
                task.getReplanCount(),
                0 // durationMs — tracked at API layer
        );
    }

    private String buildApprovalSummary(PlannedAction action, PolicyDecision policy) {
        return "Approval required for %s action. Approval ID: %s".formatted(
                action.tool(),
                policy.approvalRequest() != null ? policy.approvalRequest().getId() : "unknown");
    }

    private void emit(Task task, String type, String message) {
        try {
            eventPublisher.publishEvent(new TaskEvent(this, task.getId(), type, message));
        } catch (Exception e) {
            log.debug("Event publish error (non-critical): {}", e.getMessage());
        }
    }
}
