package com.weekendplanner.engine.tooling;

public record ToolInvocation<T>(
        String requestId,
        String userId,
        String planId,
        String caller,
        String toolName,
        T input
) {
}
