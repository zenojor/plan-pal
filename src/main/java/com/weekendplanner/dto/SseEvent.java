package com.weekendplanner.dto;

import java.util.List;

/**
 * SSE 事件 - 实时向前端推送 ReAct 步骤
 */
public record SseEvent(
        String type,       // THOUGHT / ACTION / OBSERVATION / FINISH / ERROR
        int step,
        String content,
        List<PlanStep> timeline, // PLAN_STEP/FINISH 时附带当前时间线
        String status,
        String orderGroupId,
        String notificationText,
        String degradationNote,
        String planId,
        PlanIntent intent,
        List<OrderIntent> orderIntents,
        String executionStatus,
        PlanPatch planPatch,
        ActionCard actionCard
) {
    public SseEvent {
        orderIntents = orderIntents == null ? null : List.copyOf(orderIntents);
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, null, null);
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus,
                    PlanPatch planPatch) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, planPatch, null);
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline) {
        this(type, step, content, timeline, null, null, null, null, null, null, null, null, null, null);
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                null, null, null, null, null, null);
    }
}
