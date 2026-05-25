package com.weekendplanner.dto;

import java.time.LocalDate;
import java.util.List;

public record WeatherSnapshot(
        String city,
        LocalDate date,
        String condition,
        int temperatureC,
        int precipitationProbability,
        int windLevel,
        String outdoorRiskLevel,
        String summary,
        List<String> preferredTags,
        List<String> avoidTags
) {
    public WeatherSnapshot {
        city = city == null || city.isBlank() ? "上海" : city;
        condition = condition == null || condition.isBlank() ? "CLEAR" : condition;
        outdoorRiskLevel = outdoorRiskLevel == null || outdoorRiskLevel.isBlank() ? "LOW" : outdoorRiskLevel;
        summary = summary == null ? "" : summary;
        preferredTags = preferredTags == null ? List.of() : List.copyOf(preferredTags);
        avoidTags = avoidTags == null ? List.of() : List.copyOf(avoidTags);
    }
}
