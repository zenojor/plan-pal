package com.weekendplanner.dto;

public record TicketResponse(
        String ticketId,
        boolean isSuccess,
        double totalPrice,
        String message,
        String provider,
        String traceId,
        String errorCode,
        String externalOrderId,
        String idempotencyKey
) {
    public TicketResponse(String ticketId, boolean isSuccess, double totalPrice, String message) {
        this(ticketId, isSuccess, totalPrice, message, "sandbox", "", "", ticketId, "");
    }
}
