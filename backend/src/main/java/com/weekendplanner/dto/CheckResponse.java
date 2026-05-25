package com.weekendplanner.dto;

public record CheckResponse(
        String poiId,
        String status,
        int queueTimeMinutes,
        boolean needPreOrder,
        String provider,
        String traceId,
        String errorCode,
        String message,
        String externalResourceId
) {
    public CheckResponse(String poiId, String status, int queueTimeMinutes, boolean needPreOrder) {
        this(poiId, status, queueTimeMinutes, needPreOrder, "sandbox", "", "", "", "");
    }
}
