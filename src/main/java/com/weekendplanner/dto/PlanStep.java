package com.weekendplanner.dto;

public record PlanStep(
        int durationMinutes,
        String phase,
        String action,
        String poiId,
        String poiName,
        String bookingStatus,
        String note,
        double[] lnglat,
        String audience,
        String reason,
        String budget
) {}
