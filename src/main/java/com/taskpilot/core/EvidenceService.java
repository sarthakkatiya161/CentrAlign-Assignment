package com.taskpilot.core;

import com.taskpilot.domain.Evidence;
import com.taskpilot.persistence.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores evidence artifacts produced during task execution.
 * Evidence is what proves a task was completed — screenshots, record IDs, message IDs, etc.
 */
@Component
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final EvidenceRepository evidenceRepository;

    public EvidenceService(EvidenceRepository evidenceRepository) {
        this.evidenceRepository = evidenceRepository;
    }

    public Evidence save(String taskId, String type, String data) {
        Evidence evidence = new Evidence(
                UUID.randomUUID().toString(),
                taskId,
                type,
                data,
                Instant.now()
        );
        evidenceRepository.save(evidence);
        log.info("Saved evidence: taskId={}, type={}, id={}", taskId, type, evidence.id());
        return evidence;
    }

    public List<Evidence> getForTask(String taskId) {
        return evidenceRepository.findByTaskId(taskId);
    }
}
