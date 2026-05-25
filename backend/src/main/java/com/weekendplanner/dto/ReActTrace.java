package com.weekendplanner.dto;

/**
 * ReAct 循环中每一步的思考记录
 */
public record ReActTrace(
        int step,
        String type,     // THOUGHT / ACTION / OBSERVATION / FINISH
        String content   // 详细内容
) {}
