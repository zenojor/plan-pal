package com.weekendplanner.dto;

public record PlanStep(
        int durationMinutes,
        String startTime,
        String endTime,
        String phase,
        String action,
        String poiId,
        String poiName,
        String bookingStatus,
        String note,
        double[] lnglat,
        String audience,
        String reason,
        String budget,
        int headcount,
        String constraints,
        String executionStatus,
        String orderIntentId,
        boolean isTransit,
        String transportMode,
        double distanceKm,
        String fromPoiName,
        String toPoiName
) {
    public PlanStep(int durationMinutes,
                    String startTime,
                    String endTime,
                    String phase,
                    String action,
                    String poiId,
                    String poiName,
                    String bookingStatus,
                    String note,
                    double[] lnglat,
                    String audience,
                    String reason,
                    String budget,
                    int headcount,
                    String constraints,
                    String executionStatus,
                    String orderIntentId) {
        this(durationMinutes, startTime, endTime, phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, headcount, constraints, executionStatus, orderIntentId,
                false, "", 0, "", "");
    }

    public PlanStep(int durationMinutes,
                    String phase,
                    String action,
                    String poiId,
                    String poiName,
                    String bookingStatus,
                    String note,
                    double[] lnglat,
                    String audience,
                    String reason,
                    String budget) {
        this(durationMinutes, "", "", phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, 0, "", "", "", false, "", 0, "", "");
    }
}
