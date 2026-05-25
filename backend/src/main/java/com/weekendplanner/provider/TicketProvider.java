package com.weekendplanner.provider;

public interface TicketProvider {

    TicketResult bookTickets(String poiId, int quantity, String sessionTime, String idempotencyKey);

    record TicketResult(
            String ticketId,
            boolean success,
            double totalPrice,
            String message,
            String provider,
            String traceId,
            String errorCode,
            String externalOrderId,
            String idempotencyKey
    ) {}
}
