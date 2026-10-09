package com.taskpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini implementation of the LlmClient interface.
 * Uses the standard Google Generative AI REST API.
 */
@Component
@ConditionalOnProperty(name = "taskpilot.llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiLlmClient(
            @Value("${taskpilot.llm.api-key}") String apiKey,
            @Value("${taskpilot.llm.model:gemini-1.5-pro}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String chat(String systemPrompt, List<LlmMessage> messages) {
        int retries = 0;
        int maxRetries = 3;
        while (retries < maxRetries) {
            try {
                return executeChat(systemPrompt, messages);
            } catch (LlmRateLimitException e) {
                retries++;
                if (retries >= maxRetries) {
                    throw new RuntimeException("Max retries exceeded for rate limit", e);
                }
                long backoff = (long) Math.pow(2, retries) * 2000; // longer backoff for Gemini quotas
                log.warn("Rate limited, retrying in {} ms", backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
        throw new RuntimeException("Unexpected exit from chat retry loop");
    }

    private String executeChat(String systemPrompt, List<LlmMessage> messages) {
        try {
            // Build the contents array
            List<Map<String, Object>> contents = new ArrayList<>();
            for (LlmMessage msg : messages) {
                // Map roles: standard API uses 'user' and 'model'
                String geminiRole = msg.role().equals("assistant") ? "model" : "user";
                contents.add(Map.of(
                        "role", geminiRole,
                        "parts", List.of(Map.of("text", msg.content()))
                ));
            }

            Map<String, Object> requestBodyMap = new LinkedHashMap<>();
            requestBodyMap.put("contents", contents);
            
            // Add system instruction if present
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBodyMap.put("systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ));
            }

            String requestBody = objectMapper.writeValueAsString(requestBodyMap);
            log.debug("Gemini Request: {}", truncate(requestBody, 500));

            String url = String.format(API_URL_TEMPLATE, model, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                throw new LlmRateLimitException("Rate limit exceeded");
            }
            if (response.statusCode() != 200) {
                log.error("Gemini API Error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Gemini API error: " + response.statusCode() + " " + response.body());
            }

            log.debug("Gemini Response: {}", truncate(response.body(), 500));

            JsonNode responseNode = objectMapper.readTree(response.body());
            
            // Navigate the response: candidates[0].content.parts[0].text
            JsonNode candidates = responseNode.path("candidates");
            if (candidates.isMissingNode() || candidates.isEmpty()) {
                throw new RuntimeException("No candidates returned from Gemini API");
            }
            
            return candidates.get(0).path("content").path("parts").get(0).path("text").asText();
            
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            log.error("Error communicating with Gemini", e);
            throw new RuntimeException("Failed to call Gemini API", e);
        }
    }

    @Override
    public <T> T chatStructured(String systemPrompt, List<LlmMessage> messages, Class<T> responseType) {
        int retries = 0;
        int maxRetries = 2;
        Exception lastError = null;
        while (retries <= maxRetries) {
            String responseStr = chat(systemPrompt, messages);
            try {
                String jsonStr = extractJson(responseStr);
                return objectMapper.readValue(jsonStr, responseType);
            } catch (Exception e) {
                lastError = e;
                log.warn("Failed to parse structured response on attempt {}. Error: {}", retries + 1, e.getMessage());
                retries++;
            }
        }
        throw new RuntimeException("Failed to parse structured response after " + maxRetries + " retries", lastError);
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        Pattern codeBlock = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.DOTALL);
        Matcher matcher = codeBlock.matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).strip();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                return candidate;
            }
        }
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start;
        if (objStart == -1) start = arrStart;
        else if (arrStart == -1) start = objStart;
        else start = Math.min(objStart, arrStart);
        if (start == -1) return text;
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        return (end > start) ? text.substring(start, end + 1) : text;
    }

    private String truncate(String text, int length) {
        if (text == null || text.length() <= length) return text;
        return text.substring(0, length) + "... [truncated]";
    }

    private static class LlmRateLimitException extends RuntimeException {
        public LlmRateLimitException(String message) { super(message); }
    }
}
