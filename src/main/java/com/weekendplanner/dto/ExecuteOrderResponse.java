package com.weekendplanner.dto;

public record ExecuteOrderResponse(
        String orderGroupId,
        String notifiedContact,
        String status,
        String message,
        String provider,
        String traceId,
        String errorCode
) {
    public ExecuteOrderResponse(String orderGroupId, String notifiedContact, String status, String message) {
        this(orderGroupId, notifiedContact, status, message, "sandbox", "", "");
    }
}
