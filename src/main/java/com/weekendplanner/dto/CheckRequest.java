package com.weekendplanner.dto;

/**
 * 排队/可用性查验请求
 */
public record CheckRequest(
        String poiId,
        String targetTime,
        int headcount
) {}
