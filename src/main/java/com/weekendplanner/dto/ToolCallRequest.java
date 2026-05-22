package com.weekendplanner.dto;

/**
 * 工具调用参数 - ReAct 引擎内部使用
 */
public record ToolCallRequest(
        String toolName,
        String parametersJson
) {}
