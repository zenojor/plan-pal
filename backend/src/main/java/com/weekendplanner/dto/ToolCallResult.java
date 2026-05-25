package com.weekendplanner.dto;

/**
 * 工具执行结果 - ReAct 引擎内部使用
 */
public record ToolCallResult(
        String toolName,
        boolean success,
        String resultJson,
        String errorMessage
) {}
