package com.weekendplanner.engine.graph;

import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.context.ContextPack;

import java.util.List;

public record PlanGraphState(
        String threadId,
        String userId,
        String planId,
        String userTurn,
        String mode,
        PlanRequest planRequest,
        ContextPack contextPack,
        PlanResponse response,
        String nextNode,
        List<PlanGraphEvents.PlanGraphEvent> events
) {
    public PlanGraphState {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public PlanGraphState withNext(String nextNode) {
        return new PlanGraphState(threadId, userId, planId, userTurn, mode, planRequest,
                contextPack, response, nextNode, events);
    }

    public PlanGraphState withResponse(PlanResponse response) {
        String resolvedPlanId = response == null ? planId : response.planId();
        return new PlanGraphState(threadId, userId, resolvedPlanId, userTurn, mode, planRequest,
                contextPack, response, nextNode, events);
    }
}
