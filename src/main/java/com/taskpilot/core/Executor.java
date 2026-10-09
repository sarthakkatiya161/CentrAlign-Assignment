package com.taskpilot.core;

import com.taskpilot.domain.PlannedAction;
import com.taskpilot.domain.ToolResult;
import com.taskpilot.tools.Tool;
import com.taskpilot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Executes tool actions with timeout protection.
 * NEVER throws — always returns a ToolResult (ok or failed).
 */
@Component
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    private final ToolRegistry toolRegistry;
    private final int timeoutSeconds;
    private final ExecutorService executorService;

    public Executor(ToolRegistry toolRegistry,
                    @Value("${taskpilot.agent.tool-timeout-seconds:30}") int timeoutSeconds) {
        this.toolRegistry = toolRegistry;
        this.timeoutSeconds = timeoutSeconds;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Run the action's tool. Returns ToolResult with ok=false on any error.
     * Never throws to the agent loop.
     */
    public ToolResult run(PlannedAction action) {
        long start = System.currentTimeMillis();

        Tool tool = toolRegistry.get(action.tool()).orElse(null);
        if (tool == null) {
            log.warn("Unknown tool requested: {}", action.tool());
            return ToolResult.failure("UNKNOWN_TOOL", "No tool registered with name: " + action.tool());
        }

        log.info("Executing tool={} args={}", action.tool(), sanitizeArgs(action.args()));

        try {
            Future<ToolResult> future = executorService.submit(() -> tool.execute(action.args()));
            ToolResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);

            long durationMs = System.currentTimeMillis() - start;
            log.info("Tool {} completed: ok={}, durationMs={}", action.tool(), result.ok(), durationMs);

            // Rebuild result with actual duration
            return new ToolResult(result.ok(), result.data(), result.errorType(),
                    result.errorMessage(), result.artifacts(), durationMs);

        } catch (TimeoutException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.warn("Tool {} timed out after {}s", action.tool(), timeoutSeconds);
            return new ToolResult(false, null, "TIMEOUT",
                    "Tool execution timed out after " + timeoutSeconds + "s", java.util.List.of(), durationMs);

        } catch (ExecutionException e) {
            long durationMs = System.currentTimeMillis() - start;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool {} threw exception: {}", action.tool(), cause.getMessage(), cause);
            return new ToolResult(false, null, "EXECUTION_ERROR",
                    cause.getMessage(), java.util.List.of(), durationMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tool {} execution interrupted", action.tool());
            return ToolResult.failure("INTERRUPTED", "Execution was interrupted");

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Unexpected error executing tool {}: {}", action.tool(), e.getMessage(), e);
            return new ToolResult(false, null, "UNEXPECTED_ERROR",
                    e.getMessage(), java.util.List.of(), durationMs);
        }
    }

    /** Mask any arg keys that might contain sensitive data. */
    private Object sanitizeArgs(java.util.Map<String, Object> args) {
        if (args == null) return "{}";
        var sanitized = new java.util.LinkedHashMap<>(args);
        sanitized.replaceAll((k, v) -> {
            String lower = k.toLowerCase();
            if (lower.contains("password") || lower.contains("token") || lower.contains("secret")) {
                return "[REDACTED]";
            }
            return v;
        });
        return sanitized;
    }
}
