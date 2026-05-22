package com.weekendplanner.dto;

import java.util.List;

/**
 * SSE 事件 - 实时向前端推送 ReAct 步骤
 */
public record SseEvent(
        String type,       // THOUGHT / ACTION / OBSERVATION / FINISH / ERROR
        int step,
        String content,
        List<PlanStep> timeline  // FINISH 时附带完整时间线
) {}
