package com.weekendplanner.engine.context;

import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.WeatherSnapshot;

import java.util.List;

public record PlannerContext(
        String intentSummary,
        ConstraintSet constraints,
        WeatherSnapshot weather,
        List<String> allowedTools
) {
}
