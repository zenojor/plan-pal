package com.weekendplanner.dto;

/**
 * 票务预订请求
 */
public record BookTicketRequest(
        String poiId,
        int num,
        String sessionTime
) {}
