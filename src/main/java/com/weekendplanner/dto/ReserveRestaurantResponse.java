package com.weekendplanner.dto;

public record ReserveRestaurantResponse(
        String reservationId,
        boolean success,
        String message,
        String provider,
        String traceId,
        String errorCode,
        String externalOrderId,
        String idempotencyKey
) {
    public ReserveRestaurantResponse(String reservationId, boolean success, String message) {
        this(reservationId, success, message, "sandbox", "", "", reservationId, "");
    }
}
