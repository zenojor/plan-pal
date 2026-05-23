package com.weekendplanner.dto;

/**
 * 餐厅预约响应
 */
public record ReserveRestaurantResponse(
        String reservationId,
        boolean success,
        String message
) {}
