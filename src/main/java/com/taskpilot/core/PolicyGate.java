package com.taskpilot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.*;
import com.taskpilot.persistence.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Java-enforced approval gate for EXTERNAL side-effect actions.
 * The LLM cannot bypass this gate.
 *
 * Logic:
 * - NONE     → allowed
 * - REVERSIBLE → allowed (logged)
 * - EXTERNAL → blocked until a human-approved ApprovalRequest with matching payload hash exists
 */
@Component
public class PolicyGate {

    private static final Logger log = LoggerFactory.getLogger(PolicyGate.class);

    private final ApprovalRepository approvalRepository;
    private final ObjectMapper objectMapper;

    public PolicyGate(ApprovalRepository approvalRepository, ObjectMapper objectMapper) {
        this.approvalRepository = approvalRepository;
        this.objectMapper = objectMapper;
    }

    public PolicyDecision check(Task task, PlannedAction action, com.taskpilot.tools.Tool tool) {
        SideEffect sideEffect = tool.sideEffectFor(action.args());

        return switch (sideEffect) {
            case NONE -> PolicyDecision.allow();

            case REVERSIBLE -> {
                log.debug("Reversible action permitted: tool={}", tool.name());
                yield PolicyDecision.allowWithLog("Reversible action — logged for audit");
            }

            case EXTERNAL -> checkExternalApproval(task, action);
        };
    }

    private PolicyDecision checkExternalApproval(Task task, PlannedAction action) {
        String payloadHash = hashPayload(action.args());
        String actionDigest = buildActionDigest(action);

        // Check if an approved request already exists for this exact payload
        Optional<ApprovalRequest> existing = approvalRepository.findApprovedByTaskIdAndHash(
                task.getId(), payloadHash);

        if (existing.isPresent()) {
            log.info("Found approved ApprovalRequest {} for task {}", existing.get().getId(), task.getId());
            return PolicyDecision.allow();
        }

        // No approval exists — create one and block execution
        ApprovalRequest request = new ApprovalRequest(
                UUID.randomUUID().toString(),
                task.getId(),
                actionDigest,
                payloadHash,
                ApprovalStatus.PENDING,
                null,
                Instant.now(),
                null
        );
        approvalRepository.save(request);

        log.info("Created ApprovalRequest {} for EXTERNAL action: tool={}, task={}",
                request.getId(), action.tool(), task.getId());

        return PolicyDecision.needsApproval(request);
    }

    /** SHA-256 hex of the canonical JSON of the args map. */
    String hashPayload(Map<String, Object> args) {
        try {
            // Sort keys for canonical form
            String json = objectMapper.writeValueAsString(
                    new java.util.TreeMap<>(args != null ? args : Map.of()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to hash payload: {}", e.getMessage());
            return UUID.randomUUID().toString(); // fallback: unique, won't match any existing approval
        }
    }

    private String buildActionDigest(PlannedAction action) {
        try {
            return action.tool() + ":" + objectMapper.writeValueAsString(action.args());
        } catch (Exception e) {
            return action.tool() + ":[serialization-error]";
        }
    }
}
