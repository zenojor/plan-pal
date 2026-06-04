package com.weekendplanner.dto;

public record WorkflowTrace(
        int step,
        String type,
        String content
) {
}
