package com.taskpilot.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * EmailTool: search, read, draft, send, and check status of emails via mock-mail REST API.
 * Operations: search, read, draft, send, status
 * SideEffect: EXTERNAL for send, NONE for others
 */
@Component
public class EmailTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EmailTool.class);

    private final String mockMailUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmailTool(
            @Value("${taskpilot.sim.mock-mail-url:http://localhost:3002}") String mockMailUrl,
            ObjectMapper objectMapper) {
        this.mockMailUrl = mockMailUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override public String name() { return "email"; }

    @Override public String description() {
        return "Manage emails via the mock mail service. " +
               "Operations: search (q, from, folder params), read (id), draft (to, subject, body), " +
               "send (id of draft), status (id).";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "op", Map.of("type", "string", "enum", List.of("search","read","draft","send","status","list")),
                "q", Map.of("type", "string", "description", "Search query"),
                "from", Map.of("type", "string", "description", "Filter by sender"),
                "folder", Map.of("type", "string", "description", "Filter by folder (INBOX, SENT)"),
                "id", Map.of("type", "string", "description", "Email ID"),
                "to", Map.of("type", "string", "description", "Recipient for draft"),
                "subject", Map.of("type", "string", "description", "Subject for draft"),
                "body", Map.of("type", "string", "description", "Body for draft")
            ),
            "required", List.of("op")
        );
    }

    @Override public SideEffect sideEffect() { return SideEffect.NONE; }

    @Override public SideEffect sideEffectFor(Map<String, Object> args) {
        String op = (String) args.getOrDefault("op", "");
        return "send".equals(op) ? SideEffect.EXTERNAL : SideEffect.NONE;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String op = (String) input.getOrDefault("op", "list");
        try {
            return switch (op) {
                case "search" -> searchEmails(input);
                case "list"   -> listEmails();
                case "read"   -> readEmail((String) input.get("id"));
                case "draft"  -> draftEmail(input);
                case "send"   -> sendEmail((String) input.get("id"));
                case "status" -> emailStatus((String) input.get("id"));
                default       -> ToolResult.failure("INVALID_OP", "Unknown email op: " + op);
            };
        } catch (Exception e) {
            log.error("EmailTool error: {}", e.getMessage(), e);
            return ToolResult.failure("EMAIL_ERROR", e.getMessage());
        }
    }

    private ToolResult searchEmails(Map<String, Object> input) throws Exception {
        StringBuilder url = new StringBuilder(mockMailUrl + "/api/emails/search?");
        if (input.get("q") != null)
            url.append("q=").append(URLEncoder.encode((String) input.get("q"), StandardCharsets.UTF_8)).append("&");
        if (input.get("from") != null)
            url.append("from=").append(URLEncoder.encode((String) input.get("from"), StandardCharsets.UTF_8)).append("&");
        if (input.get("folder") != null)
            url.append("folder=").append(URLEncoder.encode((String) input.get("folder"), StandardCharsets.UTF_8));

        String body = httpGet(url.toString());
        List<Map<String, Object>> results = objectMapper.readValue(body, new TypeReference<>() {});
        return ToolResult.success(Map.of("results", results, "count", results.size()));
    }

    private ToolResult listEmails() throws Exception {
        String body = httpGet(mockMailUrl + "/api/emails");
        List<Map<String, Object>> emails = objectMapper.readValue(body, new TypeReference<>() {});
        return ToolResult.success(Map.of("results", emails, "count", emails.size()));
    }

    private ToolResult readEmail(String id) throws Exception {
        if (id == null) return ToolResult.failure("INVALID_ARGS", "id is required for read");
        String body = httpGet(mockMailUrl + "/api/emails/" + id);
        Map<String, Object> email = objectMapper.readValue(body, new TypeReference<>() {});
        return ToolResult.success(email);
    }

    private ToolResult draftEmail(Map<String, Object> input) throws Exception {
        Map<String, Object> payload = Map.of(
            "to", input.getOrDefault("to", ""),
            "subject", input.getOrDefault("subject", ""),
            "body", input.getOrDefault("body", "")
        );
        String responseBody = httpPost(mockMailUrl + "/api/emails/draft", payload);
        Map<String, Object> result = objectMapper.readValue(responseBody, new TypeReference<>() {});
        return ToolResult.success(result);
    }

    private ToolResult sendEmail(String id) throws Exception {
        if (id == null) return ToolResult.failure("INVALID_ARGS", "id is required for send");
        String responseBody = httpPost(mockMailUrl + "/api/emails/" + id + "/send", Map.of());
        Map<String, Object> result = objectMapper.readValue(responseBody, new TypeReference<>() {});
        return ToolResult.success(result);
    }

    private ToolResult emailStatus(String id) throws Exception {
        if (id == null) return ToolResult.failure("INVALID_ARGS", "id is required for status");
        String body = httpGet(mockMailUrl + "/api/emails/" + id + "/status");
        Map<String, Object> result = objectMapper.readValue(body, new TypeReference<>() {});
        return ToolResult.success(result);
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + url + ": " + response.body());
        }
        return response.body();
    }

    private String httpPost(String url, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
