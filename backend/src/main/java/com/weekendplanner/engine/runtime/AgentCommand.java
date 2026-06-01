package com.weekendplanner.engine.runtime;


import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.PlanPatch;

import java.util.Map;

public record AgentCommand(
        String intent,
        double confidence,
        String targetSegmentId,
        String candidateSetId,
        Integer selectedIndex,
        Map<String, Object> slots,
        ConstraintSet constraintsDelta,
        String command,
        RouteMode routeMode,
        boolean needClarification,
        String clarificationQuestion,
        PlanPatch directPatch
) {
    public AgentCommand {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        routeMode = routeMode == null ? RouteMode.FAST_WORKFLOW : routeMode;
    }

    public static AgentCommand fast(String intent, String command, String targetSegmentId) {
        return new AgentCommand(intent, 0.9, targetSegmentId, null, null, Map.of(),
                null, command, RouteMode.FAST_WORKFLOW, false, null, null);
    }
}
