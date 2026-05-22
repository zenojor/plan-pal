package com.weekendplanner.dto;

/**
 * 票务预订响应
 */
public record TicketResponse(
        String ticketId,
        boolean isSuccess,
        double totalPrice,
        String message
) {}
