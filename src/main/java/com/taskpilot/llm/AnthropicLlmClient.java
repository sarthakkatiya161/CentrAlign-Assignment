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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "taskpilot.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicLlmClient(
            @Value("${taskpilot.llm.api-key}") String apiKey,
            @Value("${taskpilot.llm.model:claude-sonnet-4-20250514}") String model,
            @Value("${taskpilot.llm.max-tokens:4096}") int maxTokens,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
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
                long backoff = (long) Math.pow(2, retries) * 1000;
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
            Map<String, Object> requestBodyMap = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", messages.stream().map(m -> Map.of(
                            "role", m.role(),
                            "content", m.content()
                    )).toList()
            );

            String requestBody = objectMapper.writeValueAsString(requestBodyMap);
            log.debug("Anthropic Request: {}", truncate(requestBody, 500));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                throw new LlmRateLimitException("Rate limit exceeded");
            }
            if (response.statusCode() != 200) {
                log.error("Anthropic API Error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Anthropic API error: " + response.statusCode() + " " + response.body());
            }

            log.debug("Anthropic Response: {}", truncate(response.body(), 500));

            JsonNode responseNode = objectMapper.readTree(response.body());
            return responseNode.path("content").get(0).path("text").asText();
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            log.error("Error communicating with Anthropic", e);
            throw new RuntimeException("Failed to call Anthropic API", e);
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

    /**
     * Extracts JSON from the LLM response — handles markdown code blocks and raw JSON.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        // Try markdown code block: ```json { ... } ```
        Pattern codeBlock = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.DOTALL);
        Matcher matcher = codeBlock.matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).strip();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                return candidate;
            }
        }
        // Find raw JSON object or array
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
