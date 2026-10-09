package com.taskpilot.tools;

import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    Map<String, Object> inputSchema();
    SideEffect sideEffect();
    SideEffect sideEffectFor(Map<String, Object> args);
    ToolResult execute(Map<String, Object> input);
}
