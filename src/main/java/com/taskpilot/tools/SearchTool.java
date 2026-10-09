package com.taskpilot.tools;

import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SearchTool: search within available sim data sources.
 * This is a lightweight search over known sim resources.
 * SideEffect: NONE (read-only)
 */
@Component
public class SearchTool implements Tool {

    @Override public String name() { return "search"; }

    @Override public String description() {
        return "Search for information. Targets: 'jobs' (search job listings on sim company portal URL), " +
               "'email' (delegates to email tool search), 'files' (delegates to file listing). " +
               "Use this to discover relevant resources before navigating.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "What to search for"),
                "target", Map.of("type", "string", "enum", List.of("jobs","email","files","web"),
                                 "description", "Where to search")
            ),
            "required", List.of("query")
        );
    }

    @Override public SideEffect sideEffect() { return SideEffect.NONE; }
    @Override public SideEffect sideEffectFor(Map<String, Object> args) { return SideEffect.NONE; }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        String target = (String) input.getOrDefault("target", "web");

        return switch (target) {
            case "jobs" -> ToolResult.success(Map.of(
                "results", List.of(
                    Map.of("title", "AI Engineering Intern", "company", "TechCorp",
                           "url", "http://localhost:3001/apply/ai-intern",
                           "description", "Work on cutting-edge AI systems"),
                    Map.of("title", "Frontend Developer", "company", "TechCorp",
                           "url", "http://localhost:3001/apply/frontend-dev",
                           "description", "Build beautiful UIs"),
                    Map.of("title", "Data Analyst", "company", "TechCorp",
                           "url", "http://localhost:3001/apply/data-analyst",
                           "description", "Analyze business data")
                ),
                "hint", "Use the browser tool to open the application URL"
            ));
            default -> ToolResult.success(Map.of(
                "query", query,
                "hint", "Use the browser tool to navigate to http://localhost:3001, " +
                        "or use the email tool with op=search, or files tool with op=list",
                "available_resources", List.of(
                    "Company portal: http://localhost:3001",
                    "Email: use email tool with op=search",
                    "Files: use files tool with op=list"
                )
            ));
        };
    }
}
