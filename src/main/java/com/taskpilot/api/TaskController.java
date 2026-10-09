package com.taskpilot.api;

import com.taskpilot.core.AgentOrchestrator;
import com.taskpilot.core.TaskManager;
import com.taskpilot.domain.*;
import com.taskpilot.persistence.StepRepository;
import com.taskpilot.persistence.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for task lifecycle management.
 */
@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskManager taskManager;
    private final AgentOrchestrator orchestrator;
    private final TaskRepository taskRepository;
    private final StepRepository stepRepository;

    public TaskController(TaskManager taskManager, AgentOrchestrator orchestrator,
                          TaskRepository taskRepository, StepRepository stepRepository) {
        this.taskManager = taskManager;
        this.orchestrator = orchestrator;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, String> body) {
        String request = body.get("request");
        if (request == null || request.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "request is required"));
        }
        Task task = taskManager.create(request);
        log.info("Starting task {}: {}", task.getId(), request);
        orchestrator.executeTask(task.getId()); // async
        return ResponseEntity.ok(taskToMap(task));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTasks() {
        return ResponseEntity.ok(
            taskRepository.findAll().stream().map(this::taskToMap).toList()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String id) {
        return taskManager.get(id)
            .map(task -> {
                Map<String, Object> response = taskToMap(task);
                response.put("steps", stepRepository.findByTaskId(id).stream()
                    .map(this::stepToMap).toList());
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String id) {
        taskManager.cancel(id);
        return taskManager.get(id)
            .map(t -> ResponseEntity.ok(taskToMap(t)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/input")
    public ResponseEntity<Map<String, Object>> provideInput(@PathVariable String id,
                                                             @RequestBody Map<String, String> body) {
        String answer = body.get("answer");
        if (answer == null || answer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "answer is required"));
        }
        return taskManager.get(id).map(task -> {
            if (task.getStatus() != TaskStatus.WAITING_INPUT) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "Task is not waiting for input, status: " + task.getStatus());
                return ResponseEntity.badRequest().body(err);
            }
            orchestrator.resumeAfterInput(id, answer);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("message", "Input received, task resuming");
            return ResponseEntity.ok(ok);
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> taskToMap(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("request", task.getRequest());
        m.put("status", task.getStatus().name());
        m.put("stepCount", task.getStepCount());
        m.put("replanCount", task.getReplanCount());
        m.put("createdAt", task.getCreatedAt().toString());
        m.put("updatedAt", task.getUpdatedAt().toString());
        m.put("facts", task.getFacts());
        m.put("objective", task.getGoalSpec() != null ? task.getGoalSpec().objective() : null);
        return m;
    }

    private Map<String, Object> stepToMap(StepRecord step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepNo", step.stepNo());
        m.put("tool", step.action() != null ? step.action().tool() : "?");
        m.put("rationale", step.action() != null ? step.action().rationale() : "");
        m.put("summary", step.observation() != null ? step.observation().summary() : "");
        m.put("failed", step.observation() != null && step.observation().failed());
        m.put("recovery", step.recoveryDecision() != null ? step.recoveryDecision().name() : "");
        m.put("timestamp", step.timestamp().toString());
        return m;
    }
}
