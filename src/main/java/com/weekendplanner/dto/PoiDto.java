package com.weekendplanner.dto;

import java.util.List;

/**
 * POI 数据传输对象
 */
public record PoiDto(
        String poiId,
        String name,
        String category,
        double distanceKm,
        int recommendedDurationMinutes,
        List<String> tags
) {}
