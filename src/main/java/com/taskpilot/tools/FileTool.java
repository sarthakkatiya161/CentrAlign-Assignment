package com.taskpilot.tools;

import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * FileTool: list, read, and update files in the candidate-data workspace.
 * Operations: list, read, write_profile
 * SideEffect: NONE for list/read, REVERSIBLE for write_profile
 */
@Component
public class FileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileTool.class);

    private final String dataDir;

    public FileTool(@Value("${taskpilot.files.data-dir:candidate-data}") String dataDir) {
        this.dataDir = dataDir;
    }

    @Override public String name() { return "files"; }

    @Override public String description() {
        return "Manage local candidate files. Operations: list (list files), read (read file content), write_profile (update profile.json).";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "op", Map.of("type", "string", "enum", List.of("list", "read", "write_profile")),
                "path", Map.of("type", "string", "description", "Relative path from data dir (for read)"),
                "updates", Map.of("type", "object", "description", "Key-value updates for write_profile")
            ),
            "required", List.of("op")
        );
    }

    @Override public SideEffect sideEffect() { return SideEffect.NONE; }

    @Override public SideEffect sideEffectFor(Map<String, Object> args) {
        String op = (String) args.getOrDefault("op", "");
        return "write_profile".equals(op) ? SideEffect.REVERSIBLE : SideEffect.NONE;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String op = (String) input.getOrDefault("op", "list");
        try {
            return switch (op) {
                case "list"          -> listFiles();
                case "read"          -> readFile((String) input.get("path"));
                case "write_profile" -> writeProfile(input);
                default              -> ToolResult.failure("INVALID_OP", "Unknown op: " + op);
            };
        } catch (Exception e) {
            log.error("FileTool error: {}", e.getMessage(), e);
            return ToolResult.failure("FILE_ERROR", e.getMessage());
        }
    }

    private ToolResult listFiles() throws Exception {
        Path dir = Paths.get(dataDir);
        if (!Files.exists(dir)) {
            return ToolResult.failure("NOT_FOUND", "Data directory not found: " + dataDir);
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                  .forEach(p -> files.add(dir.relativize(p).toString()));
        }
        return ToolResult.success(Map.of("files", files, "count", files.size()));
    }

    private ToolResult readFile(String relativePath) throws Exception {
        if (relativePath == null || relativePath.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "path is required for read operation");
        }
        // Security: prevent path traversal
        Path base = Paths.get(dataDir).toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) {
            return ToolResult.failure("SECURITY_ERROR", "Path traversal attempt detected");
        }
        if (!Files.exists(target)) {
            return ToolResult.failure("NOT_FOUND", "File not found: " + relativePath);
        }
        String content = Files.readString(target);
        return ToolResult.success(Map.of("path", relativePath, "content", content, "size", content.length()));
    }

    @SuppressWarnings("unchecked")
    private ToolResult writeProfile(Map<String, Object> input) throws Exception {
        Object updatesObj = input.get("updates");
        if (!(updatesObj instanceof Map)) {
            return ToolResult.failure("INVALID_ARGS", "updates must be an object");
        }
        Map<String, Object> updates = (Map<String, Object>) updatesObj;

        Path profilePath = Paths.get(dataDir, "profile.json");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        Map<String, Object> profile = new LinkedHashMap<>();
        if (Files.exists(profilePath)) {
            profile = mapper.readValue(profilePath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
        profile.putAll(updates);
        mapper.writerWithDefaultPrettyPrinter().writeValue(profilePath.toFile(), profile);

        return ToolResult.success(Map.of("updated", true, "fields", new ArrayList<>(updates.keySet())));
    }
}
