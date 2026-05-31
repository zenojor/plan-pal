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
        ExperiencePreference experiencePreference
) {
    public ConstraintSet {
        participants = participants == null ? List.of() : List.copyOf(participants);
        dietaryConstraints = dietaryConstraints == null ? List.of() : List.copyOf(dietaryConstraints);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
        experiencePreference = experiencePreference == null ? ExperiencePreference.empty() : experiencePreference;
    }

    public static ConstraintSet fromIntent(PlanIntent intent) {
        if (intent == null) {
            return new ConstraintSet(null, null, null, null, List.of(), null, null,
                    null, null, List.of(), List.of(), List.of(), false, null, false, null, null,
                    ExperiencePreference.empty());
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
                ExperiencePreference.empty());
    }

    public ConstraintSet withExperiencePreference(ExperiencePreference preference) {
        return new ConstraintSet(startTime, endTime, totalMinutes, headcount, participants, sceneType,
                budgetLevel, preferredTransportMode, locationScope, dietaryConstraints, avoid, mustHave,
                hasChildren, childAge, weatherSensitive, maxDistanceKm, maxWalkMinutes,
                experiencePreference.merge(preference));
    }

    public ConstraintSet withPlanningContext(String nextStartTime,
                                             String nextEndTime,
                                             Integer nextTotalMinutes,
                                             Integer nextHeadcount,
                                             String nextLocationScope,
                                             ExperiencePreference preference) {
        return new ConstraintSet(
                firstNonBlank(nextStartTime, startTime),
                firstNonBlank(nextEndTime, endTime),
                nextTotalMinutes == null || nextTotalMinutes <= 0 ? totalMinutes : nextTotalMinutes,
                nextHeadcount == null || nextHeadcount <= 0 ? headcount : nextHeadcount,
                participants,
                sceneType,
                budgetLevel,
                preferredTransportMode,
                firstNonBlank(nextLocationScope, locationScope),
                dietaryConstraints,
                avoid,
                mustHave,
                hasChildren,
                childAge,
                weatherSensitive,
                maxDistanceKm,
                maxWalkMinutes,
                experiencePreference.merge(preference));
    }

    public ConstraintSet mergeIntent(PlanIntent intent) {
        if (intent == null) return this;
        return new ConstraintSet(
                firstNonBlank(startTime, intent.startTime()),
                firstNonBlank(endTime, intent.endTime()),
                totalMinutes == null || totalMinutes <= 0 ? intent.totalMinutes() : totalMinutes,
                headcount == null || headcount <= 0 ? intent.headcount() : headcount,
                participants.isEmpty() ? intent.participants() : participants,
                firstNonBlank(sceneType, intent.sceneType()),
                firstNonBlank(budgetLevel, intent.budgetLevel()),
                firstNonBlank(preferredTransportMode, intent.preferredTransportMode()),
                firstNonBlank(locationScope, intent.locationScope()),
                dietaryConstraints.isEmpty() ? intent.dietaryConstraints() : dietaryConstraints,
                avoid.isEmpty() ? intent.avoid() : avoid,
                mustHave.isEmpty() ? intent.mustHave() : mustHave,
                hasChildren || intent.hasChildren(),
                childAge == null ? intent.childAge() : childAge,
                weatherSensitive || intent.weatherSensitive(),
                maxDistanceKm,
                maxWalkMinutes,
                experiencePreference);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
