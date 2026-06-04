package com.weekendplanner.engine.tooling;

public record ToolSpec(
        String name,
        String description,
        ToolEffect effect,
        Class<?> inputType,
        Class<?> outputType,
        long timeoutMs,
        boolean idempotent
) {
    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name is required");
        }
        effect = effect == null ? ToolEffect.READ_ONLY : effect;
        inputType = inputType == null ? Object.class : inputType;
        outputType = outputType == null ? Object.class : outputType;
        timeoutMs = timeoutMs <= 0 ? 3_000L : timeoutMs;
    }
}
