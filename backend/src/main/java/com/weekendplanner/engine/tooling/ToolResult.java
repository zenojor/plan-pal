package com.weekendplanner.engine.tooling;

public record ToolResult<T>(
        String toolName,
        boolean success,
        T data,
        String errorMessage,
        ToolEffect effect,
        String traceId,
        long elapsedMs
) {
    public String resultJson() {
        return data == null ? null : String.valueOf(data);
    }
}
