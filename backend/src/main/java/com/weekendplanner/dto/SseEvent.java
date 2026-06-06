package com.weekendplanner.dto;

import java.util.List;

public record SseEvent(
        String type,
        int step,
        String content,
        List<PlanStep> timeline,
        String status,
        String orderGroupId,
        String notificationText,
        String degradationNote,
        String planId,
        PlanIntent intent,
        List<OrderIntent> orderIntents,
        String executionStatus,
        PlanPatch planPatch,
        ActionCard actionCard,
        PlanDelta planDelta,
        List<Conflict> conflicts,
        List<RepairOption> repairOptions,
        int version,
        PlanStatus planStatus,
        WeatherSnapshot weather,
        String summary,
        List<PlanResponse> variants
) {
    public SseEvent {
        orderIntents = orderIntents == null ? null : List.copyOf(orderIntents);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        repairOptions = repairOptions == null ? List.of() : List.copyOf(repairOptions);
        variants = variants == null ? List.of() : List.copyOf(variants);
        version = version <= 0 ? 1 : version;
        planStatus = planStatus == null ? PlanStatus.PENDING_CONFIRMATION : planStatus;
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus,
                    PlanPatch planPatch, ActionCard actionCard, PlanDelta planDelta,
                    List<Conflict> conflicts, List<RepairOption> repairOptions,
                    int version, PlanStatus planStatus, WeatherSnapshot weather) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, planPatch, actionCard, planDelta,
                conflicts, repairOptions, version, planStatus, weather, null, List.of());
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, null, null, null, List.of(), List.of(),
                1, PlanStatus.PENDING_CONFIRMATION, null, null, List.of());
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus,
                    PlanPatch planPatch) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, planPatch, null, null, List.of(), List.of(),
                1, PlanStatus.PENDING_CONFIRMATION, null, null, List.of());
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote,
                    String planId, PlanIntent intent, List<OrderIntent> orderIntents, String executionStatus,
                    PlanPatch planPatch, ActionCard actionCard) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                planId, intent, orderIntents, executionStatus, planPatch, actionCard, null, List.of(), List.of(),
                1, PlanStatus.PENDING_CONFIRMATION, null, null, List.of());
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline) {
        this(type, step, content, timeline, null, null, null, null, null, null, null, null,
                null, null, null, List.of(), List.of(), 1, PlanStatus.PENDING_CONFIRMATION, null, null, List.of());
    }

    public SseEvent(String type, int step, String content, List<PlanStep> timeline,
                    String status, String orderGroupId, String notificationText, String degradationNote) {
        this(type, step, content, timeline, status, orderGroupId, notificationText, degradationNote,
                null, null, null, null, null, null, null, List.of(), List.of(),
                1, PlanStatus.PENDING_CONFIRMATION, null, null, List.of());
    }
}
