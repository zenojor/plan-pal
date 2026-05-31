package com.weekendplanner.dto;

import java.util.LinkedHashSet;
import java.util.List;

public record ExperiencePreference(
        List<String> moods,
        String interactionLevel,
        String formalityLevel,
        String budgetMood,
        String weatherPolicy,
        List<String> activityBiases,
        List<String> avoid,
        String timeHint,
        String locationHint
) {
    public ExperiencePreference {
        moods = copyDistinct(moods);
        activityBiases = copyDistinct(activityBiases);
        avoid = copyDistinct(avoid);
    }

    public static ExperiencePreference empty() {
        return new ExperiencePreference(List.of(), null, null, null, null, List.of(), List.of(), null, null);
    }

    public static ExperiencePreference fromPreferenceKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase();
        return switch (normalized) {
            case "ritual" -> new ExperiencePreference(
                    List.of("ritual", "warm"),
                    "low_to_medium",
                    "polished",
                    "balanced",
                    null,
                    List.of("dessert", "view", "exhibition", "drinks", "dining"),
                    List.of("too_formal", "too_expensive", "awkward_silence"),
                    null,
                    null);
            case "topic_safe" -> new ExperiencePreference(
                    List.of("topic_safe", "warm"),
                    "medium",
                    "casual",
                    "balanced",
                    null,
                    List.of("exhibition", "movie", "bookstore", "live"),
                    List.of("awkward_silence", "too_loud"),
                    null,
                    null);
            case "budget_friendly" -> new ExperiencePreference(
                    List.of("relaxed"),
                    "low",
                    "casual",
                    "budget_friendly",
                    null,
                    List.of("walk", "park", "dessert", "cafe"),
                    List.of("too_expensive", "long_wait"),
                    null,
                    null);
            case "weather_safe" -> new ExperiencePreference(
                    List.of("safe", "relaxed"),
                    "medium",
                    "casual",
                    "balanced",
                    "indoor_first",
                    List.of("indoor", "exhibition", "movie", "cafe"),
                    List.of("weather_risk", "too_far"),
                    null,
                    null);
            default -> new ExperiencePreference(
                    List.of("relaxed"),
                    "low",
                    "casual",
                    "balanced",
                    null,
                    List.of("cafe", "dessert", "walk"),
                    List.of("too_formal", "awkward_silence"),
                    null,
                    null);
        };
    }

    public boolean isEmpty() {
        return moods.isEmpty()
                && blank(interactionLevel)
                && blank(formalityLevel)
                && blank(budgetMood)
                && blank(weatherPolicy)
                && activityBiases.isEmpty()
                && avoid.isEmpty()
                && blank(timeHint)
                && blank(locationHint);
    }

    public ExperiencePreference merge(ExperiencePreference other) {
        if (other == null || other.isEmpty()) return this;
        return new ExperiencePreference(
                mergeList(moods, other.moods),
                firstNonBlank(other.interactionLevel, interactionLevel),
                firstNonBlank(other.formalityLevel, formalityLevel),
                firstNonBlank(other.budgetMood, budgetMood),
                firstNonBlank(other.weatherPolicy, weatherPolicy),
                mergeList(activityBiases, other.activityBiases),
                mergeList(avoid, other.avoid),
                firstNonBlank(other.timeHint, timeHint),
                firstNonBlank(other.locationHint, locationHint));
    }

    public ExperiencePreference withContext(String nextTimeHint, String nextLocationHint) {
        return new ExperiencePreference(moods, interactionLevel, formalityLevel, budgetMood, weatherPolicy,
                activityBiases, avoid, firstNonBlank(nextTimeHint, timeHint), firstNonBlank(nextLocationHint, locationHint));
    }

    private static List<String> copyDistinct(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private static List<String> mergeList(List<String> first, List<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        result.removeIf(value -> value == null || value.isBlank());
        return List.copyOf(result);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
