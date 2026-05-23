package com.weekendplanner.dto;

import java.util.List;

public record PoiDto(
        String poiId,
        String name,
        String category,
        double lng,
        double lat,
        double distanceKm,
        int recommendedDurationMinutes,
        List<String> tags
) {
    public double[] lnglat() {
        return new double[]{lng, lat};
    }
}
