package com.taskpilot.llm;

import java.util.List;

public interface LlmClient {
    String chat(String systemPrompt, List<LlmMessage> messages);
    <T> T chatStructured(String systemPrompt, List<LlmMessage> messages, Class<T> responseType);
}
