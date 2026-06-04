package com.weekendplanner.dto;

import java.util.List;

public record PlanResponse(
        String planId,
        String userId,
        String status,
        String summary,
        List<PlanStep> timeline,
        List<WorkflowTrace> trace,
        String orderGroupId,
        String notificationText,
        String degradationNote,
        PlanIntent intent,
        List<OrderIntent> orderIntents,
        String executionStatus,
        int version,
        PlanStatus planStatus,
        List<Conflict> conflicts,
        List<RepairOption> repairOptions,
        WeatherSnapshot weather
) {
    public PlanResponse {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        trace = trace == null ? List.of() : List.copyOf(trace);
        orderIntents = orderIntents == null ? List.of() : List.copyOf(orderIntents);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        repairOptions = repairOptions == null ? List.of() : List.copyOf(repairOptions);
        version = version <= 0 ? 1 : version;
        planStatus = planStatus == null ? PlanStatus.PENDING_CONFIRMATION : planStatus;
    }

    public PlanResponse(String planId,
                        String userId,
                        String status,
                        String summary,
                        List<PlanStep> timeline,
                        List<WorkflowTrace> trace,
                        String orderGroupId,
                        String notificationText,
                        String degradationNote,
                        PlanIntent intent,
                        List<OrderIntent> orderIntents,
                        String executionStatus) {
        this(planId, userId, status, summary, timeline, trace, orderGroupId, notificationText,
                degradationNote, intent, orderIntents, executionStatus, 1,
                PlanStatus.PENDING_CONFIRMATION, List.of(), List.of(), null);
    }

    public PlanResponse(String planId,
                        String userId,
                        String status,
                        String summary,
                        List<PlanStep> timeline,
                        List<WorkflowTrace> trace,
                        String orderGroupId,
                        String notificationText,
                        String degradationNote) {
        this(planId, userId, status, summary, timeline, trace, orderGroupId, notificationText,
                degradationNote, null, List.of(), status, 1, PlanStatus.PENDING_CONFIRMATION,
                List.of(), List.of(), null);
    }
}
