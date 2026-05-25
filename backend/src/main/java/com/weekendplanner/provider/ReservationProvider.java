package com.weekendplanner.provider;

public interface ReservationProvider {

    ReservationResult reserve(String poiId, int headcount, String targetTime, String idempotencyKey);

    record ReservationResult(
            String reservationId,
            boolean success,
            String message,
            String provider,
            String traceId,
            String errorCode,
            String externalOrderId,
            String idempotencyKey
    ) {}
}
