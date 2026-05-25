package com.weekendplanner.dto;

import java.util.List;

public record ConstraintSet(
        String startTime,
        String endTime,
        Integer totalMinutes,
        Integer headcount,
        List<String> participants,
        String sceneType,
        String budgetLevel,
        String preferredTransportMode,
        String locationScope,
        List<String> dietaryConstraints,
        List<String> avoid,
        List<String> mustHave,
        boolean hasChildren,
        Integer childAge,
        boolean weatherSensitive,
        Integer maxDistanceKm,
        Integer maxWalkMinutes
) {
    public ConstraintSet {
        participants = participants == null ? List.of() : List.copyOf(participants);
        dietaryConstraints = dietaryConstraints == null ? List.of() : List.copyOf(dietaryConstraints);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
    }

    public static ConstraintSet fromIntent(PlanIntent intent) {
        if (intent == null) {
            return new ConstraintSet(null, null, null, null, List.of(), null, null,
                    null, null, List.of(), List.of(), List.of(), false, null, false, null, null);
        }
        return new ConstraintSet(
                intent.startTime(),
                intent.endTime(),
                intent.totalMinutes(),
                intent.headcount(),
                intent.participants(),
                intent.sceneType(),
                intent.budgetLevel(),
                intent.preferredTransportMode(),
                intent.locationScope(),
                intent.dietaryConstraints(),
                intent.avoid(),
                intent.mustHave(),
                intent.hasChildren(),
                intent.childAge(),
                intent.weatherSensitive(),
                null,
                null);
    }
}
