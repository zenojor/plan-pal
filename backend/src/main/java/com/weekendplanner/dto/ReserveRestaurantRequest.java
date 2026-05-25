package com.weekendplanner.dto;

/**
 * 餐厅预约请求
 */
public record ReserveRestaurantRequest(
        String poiId,
        int headcount,
        String targetTime
) {}
