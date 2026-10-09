package com.taskpilot.api;

import com.taskpilot.core.AgentOrchestrator;
import com.taskpilot.core.TaskManager;
import com.taskpilot.domain.ApprovalRequest;
import com.taskpilot.domain.ApprovalStatus;
import com.taskpilot.domain.Task;
import com.taskpilot.domain.TaskStatus;
import com.taskpilot.persistence.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for human approval workflow.
 *
 * Approval flow:
 * 1. Agent triggers EXTERNAL action → PolicyGate creates ApprovalRequest → task → WAITING_APPROVAL
 * 2. UI shows approval card via SSE
 * 3. Human sends POST /api/tasks/{id}/approvals/{aid} with APPROVE/EDIT/REJECT
 * 4. If approved, orchestrator resumes
 */
@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalRepository approvalRepository;
    private final TaskManager taskManager;
    private final AgentOrchestrator orchestrator;

    public ApprovalController(ApprovalRepository approvalRepository,
                               TaskManager taskManager,
                               AgentOrchestrator orchestrator) {
        this.approvalRepository = approvalRepository;
        this.taskManager = taskManager;
        this.orchestrator = orchestrator;
    }

    /** GET /api/tasks/{id}/approvals — get pending approval for a task. */
    @GetMapping("/{id}/approvals/pending")
    public ResponseEntity<Map<String, Object>> getPendingApproval(@PathVariable String id) {
        return approvalRepository.findPendingByTaskId(id)
            .map(req -> ResponseEntity.ok(approvalToMap(req)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/tasks/{id}/approvals/{aid}
     * Body: { "decision": "APPROVE" | "EDIT" | "REJECT", "editedPayload": {...} }
     */
    @PostMapping("/{taskId}/approvals/{approvalId}")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable String taskId,
            @PathVariable String approvalId,
            @RequestBody Map<String, Object> body) {

        String decision = (String) body.get("decision");
        if (decision == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision is required (APPROVE/EDIT/REJECT)"));
        }

        ApprovalRequest approval = approvalRepository.findById(approvalId).orElse(null);
        if (approval == null) {
            return ResponseEntity.notFound().build();
        }
        if (!approval.getTaskId().equals(taskId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Approval does not belong to this task"));
        }
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of("error", "Approval already resolved: " + approval.getStatus()));
        }

        Task task = taskManager.get(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();

        @SuppressWarnings("unchecked")
        Map<String, Object> editedPayload = (Map<String, Object>) body.get("editedPayload");

        switch (decision.toUpperCase()) {
            case "APPROVE" -> {
                approvalRepository.updateStatus(approvalId, ApprovalStatus.APPROVED, null);
                log.info("Approval {} APPROVED for task {}", approvalId, taskId);
                orchestrator.resumeAfterApproval(taskId);
                return ResponseEntity.ok(Map.of("status", "APPROVED", "message", "Task resuming"));
            }
            case "EDIT" -> {
                if (editedPayload == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "editedPayload required for EDIT decision"));
                }
                approvalRepository.updateStatus(approvalId, ApprovalStatus.EDITED, editedPayload);
                log.info("Approval {} EDITED for task {}", approvalId, taskId);
                orchestrator.resumeAfterApproval(taskId);
                return ResponseEntity.ok(Map.of("status", "EDITED", "message", "Task resuming with edited payload"));
            }
            case "REJECT" -> {
                approvalRepository.updateStatus(approvalId, ApprovalStatus.REJECTED, null);
                taskManager.cancel(taskId);
                log.info("Approval {} REJECTED for task {} — task cancelled", approvalId, taskId);
                return ResponseEntity.ok(Map.of("status", "REJECTED", "message", "Task cancelled"));
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown decision: " + decision));
            }
        }
    }

    private Map<String, Object> approvalToMap(ApprovalRequest req) {
        return Map.of(
            "id", req.getId(),
            "taskId", req.getTaskId(),
            "actionDigest", req.getActionDigest(),
            "status", req.getStatus().name(),
            "createdAt", req.getCreatedAt().toString()
        );
    }
}
