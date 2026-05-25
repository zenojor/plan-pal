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
        String toPoiName,
        String source,
        String address,
        String telephone,
        String businessHours,
        String typeCode,
        String segmentId
) {
    public PlanStep {
        segmentId = segmentId == null ? "" : segmentId;
        source = source == null ? "" : source;
        address = address == null ? "" : address;
        telephone = telephone == null ? "" : telephone;
        businessHours = businessHours == null ? "" : businessHours;
        typeCode = typeCode == null ? "" : typeCode;
    }

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
                    String orderIntentId,
                    boolean isTransit,
                    String transportMode,
                    double distanceKm,
                    String fromPoiName,
                    String toPoiName) {
        this(durationMinutes, startTime, endTime, phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, headcount, constraints, executionStatus, orderIntentId,
                isTransit, transportMode, distanceKm, fromPoiName, toPoiName, "", "", "", "", "", "");
    }

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
                false, "", 0, "", "", "", "", "", "", "", "");
    }

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
                    String orderIntentId,
                    String segmentId) {
        this(durationMinutes, startTime, endTime, phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, headcount, constraints, executionStatus, orderIntentId,
                false, "", 0, "", "", "", "", "", "", "", segmentId);
    }

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
                    String orderIntentId,
                    boolean isTransit,
                    String transportMode,
                    double distanceKm,
                    String fromPoiName,
                    String toPoiName,
                    String source,
                    String address,
                    String telephone,
                    String businessHours,
                    String typeCode) {
        this(durationMinutes, startTime, endTime, phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, headcount, constraints, executionStatus, orderIntentId,
                isTransit, transportMode, distanceKm, fromPoiName, toPoiName, source, address, telephone,
                businessHours, typeCode, "");
    }

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
                    String orderIntentId,
                    String source,
                    String address,
                    String telephone,
                    String businessHours,
                    String typeCode,
                    String segmentId) {
        this(durationMinutes, startTime, endTime, phase, action, poiId, poiName, bookingStatus, note, lnglat,
                audience, reason, budget, headcount, constraints, executionStatus, orderIntentId,
                false, "", 0, "", "", source, address, telephone, businessHours, typeCode, segmentId);
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
                audience, reason, budget, 0, "", "", "", false, "", 0, "", "", "", "", "", "", "", "");
    }
}
