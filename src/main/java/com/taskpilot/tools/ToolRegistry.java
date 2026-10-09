package com.taskpilot.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    
    private final Map<String, Tool> toolMap;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<Tool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.toolMap = tools.stream().collect(Collectors.toMap(Tool::name, t -> t));
        log.info("Registered tools: {}", toolMap.keySet());
    }

    public void register(Tool tool) {
        toolMap.put(tool.name(), tool);
        log.info("Registered tool dynamically: {}", tool.name());
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    public List<Tool> list() {
        return List.copyOf(toolMap.values());
    }

    public String menuForLLM() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : toolMap.values()) {
            sb.append("Tool: ").append(tool.name()).append("\n");
            sb.append("Description: ").append(tool.description()).append("\n");
            try {
                sb.append("Input Schema: ").append(objectMapper.writeValueAsString(tool.inputSchema())).append("\n\n");
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize input schema for tool {}", tool.name(), e);
            }
        }
        return sb.toString();
    }
}
