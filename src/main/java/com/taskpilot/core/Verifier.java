package com.taskpilot.core;

import com.taskpilot.domain.*;
import com.taskpilot.llm.LlmClient;
import com.taskpilot.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies task success using read-only probes INDEPENDENT of the executor.
 * NEVER trusts the executor's return value as proof of success.
 *
 * Verification methods:
 * - api_check   → HTTP GET to sim backend
 * - email_check → HTTP GET to mock mail API
 * - file_check  → read local file
 * - page_check  → HTTP GET and check page content
 * - llm_judge   → LLM evaluates evidence vs criteria
 */
@Component
public class Verifier {

    private static final Logger log = LoggerFactory.getLogger(Verifier.class);

    private static final String JUDGE_SYSTEM_PROMPT = """
            You are a verification judge. Based on the provided evidence, determine whether the success criterion is met.
            Respond with ONLY "PASS" or "FAIL", followed by a brief explanation.
            Format: PASS: <reason> or FAIL: <reason>
            """;

    private final LlmClient llmClient;
    private final HttpClient httpClient;
    private final String companyPortalUrl;
    private final String mockMailUrl;

    public Verifier(LlmClient llmClient,
                    @Value("${taskpilot.sim.company-portal-url:http://localhost:3001}") String companyPortalUrl,
                    @Value("${taskpilot.sim.mock-mail-url:http://localhost:3002}") String mockMailUrl) {
        this.llmClient = llmClient;
        this.companyPortalUrl = companyPortalUrl;
        this.mockMailUrl = mockMailUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public VerificationResult verify(GoalSpec goalSpec, TaskContext context) {
        log.info("Verifying task {} with {} criteria",
                context.task().getId(), goalSpec.successCriteria().size());

        List<CriterionResult> results = new ArrayList<>();

        for (SuccessCriterion criterion : goalSpec.successCriteria()) {
            CriterionResult result = verifyCriterion(criterion, context);
            results.add(result);
            log.info("Criterion '{}': {} — {}",
                    criterion.description(), result.passed() ? "PASS" : "FAIL", result.detail());
        }

        return new VerificationResult(results);
    }

    private CriterionResult verifyCriterion(SuccessCriterion criterion, TaskContext context) {
        try {
            return switch (criterion.verificationMethod()) {
                case "api_check"   -> apiCheck(criterion, context);
                case "email_check" -> emailCheck(criterion, context);
                case "file_check"  -> fileCheck(criterion, context);
                case "page_check"  -> pageCheck(criterion, context);
                case "llm_judge"   -> llmJudge(criterion, context);
                default            -> llmJudge(criterion, context);
            };
        } catch (Exception e) {
            log.error("Verification failed for criterion '{}': {}", criterion.description(), e.getMessage());
            return new CriterionResult(criterion.description(), false, null,
                    "Verification error: " + e.getMessage());
        }
    }

    private CriterionResult apiCheck(SuccessCriterion criterion, TaskContext context) throws Exception {
        // Check sim applications API
        String facts = context.task().getFacts().toString();
        String url = companyPortalUrl + "/api/applications";

        String responseBody = httpGet(url);
        // LLM judges whether the response satisfies the criterion
        return llmJudgeWithEvidence(criterion, responseBody, url);
    }

    private CriterionResult emailCheck(SuccessCriterion criterion, TaskContext context) throws Exception {
        // Check mock mail API for sent emails
        String url = mockMailUrl + "/api/emails";
        String responseBody = httpGet(url);
        return llmJudgeWithEvidence(criterion, responseBody, url);
    }

    private CriterionResult fileCheck(SuccessCriterion criterion, TaskContext context) throws Exception {
        // Read back the profile file to verify
        String profilePath = "candidate-data/profile.json";
        try {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(profilePath));
            return llmJudgeWithEvidence(criterion, content, profilePath);
        } catch (Exception e) {
            return new CriterionResult(criterion.description(), false, null,
                    "Could not read file: " + e.getMessage());
        }
    }

    private CriterionResult pageCheck(SuccessCriterion criterion, TaskContext context) throws Exception {
        // Check a known confirmation URL
        String url = companyPortalUrl + "/api/applications";
        String responseBody = httpGet(url);
        return llmJudgeWithEvidence(criterion, responseBody, url);
    }

    private CriterionResult llmJudge(SuccessCriterion criterion, TaskContext context) {
        // Build evidence from task facts and recent history
        String evidence = "Task facts: " + context.task().getFacts() +
                "\nSteps completed: " + context.task().getStepCount() +
                "\nLast observation: " + (context.lastObservation() != null ?
                        context.lastObservation().summary() : "none");
        return llmJudgeWithEvidence(criterion, evidence, null);
    }

    private CriterionResult llmJudgeWithEvidence(SuccessCriterion criterion, String evidence, String evidenceRef) {
        try {
            String prompt = """
                    Success criterion: %s
                    
                    Evidence (from independent probe):
                    %s
                    
                    Is this criterion met? Respond PASS or FAIL with reason.
                    """.formatted(criterion.description(), truncate(evidence, 2000));

            String response = llmClient.chat(JUDGE_SYSTEM_PROMPT, List.of(LlmMessage.user(prompt)));
            boolean passed = response.trim().toUpperCase().startsWith("PASS");
            String detail = response.trim();

            return new CriterionResult(criterion.description(), passed, evidenceRef, detail);
        } catch (Exception e) {
            log.error("LLM judge failed for criterion: {}", criterion.description(), e);
            throw new RuntimeException("Verification unavailable due to LLM error: " + e.getMessage(), e);
        }
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
