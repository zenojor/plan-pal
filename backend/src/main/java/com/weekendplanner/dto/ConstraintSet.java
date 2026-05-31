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
        Integer maxWalkMinutes,
        String dateStyle,
        String preferredInteractionLevel,
        String budgetMood,
        String weatherTolerance,
        String locationHint
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
                    null, null, List.of(), List.of(), List.of(), false, null, false, null, null,
                    null, null, null, null, null);
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
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public ConstraintSet withPreference(String dateStyle,
                                        String preferredInteractionLevel,
                                        String budgetMood,
                                        String weatherTolerance,
                                        String locationHint) {
        return new ConstraintSet(startTime, endTime, totalMinutes, headcount, participants, sceneType,
                budgetLevel, preferredTransportMode, locationScope, dietaryConstraints, avoid, mustHave,
                hasChildren, childAge, weatherSensitive, maxDistanceKm, maxWalkMinutes,
                dateStyle == null ? this.dateStyle : dateStyle,
                preferredInteractionLevel == null ? this.preferredInteractionLevel : preferredInteractionLevel,
                budgetMood == null ? this.budgetMood : budgetMood,
                weatherTolerance == null ? this.weatherTolerance : weatherTolerance,
                locationHint == null ? this.locationHint : locationHint);
    }
}
