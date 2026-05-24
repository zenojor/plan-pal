package com.weekendplanner.dto;

import java.util.List;

public record PoiDto(
        String poiId,
        String source,
        String name,
        String category,
        double lng,
        double lat,
        double distanceKm,
        int recommendedDurationMinutes,
        List<String> tags,
        String address,
        String telephone,
        String businessHours,
        String typeCode
) {
    public PoiDto(String poiId,
                  String name,
                  String category,
                  double lng,
                  double lat,
                  double distanceKm,
                  int recommendedDurationMinutes,
                  List<String> tags) {
        this(poiId, "sandbox", name, category, lng, lat, distanceKm, recommendedDurationMinutes, tags,
                "", "", "", "");
    }

    public double[] lnglat() {
        return new double[]{lng, lat};
    }
}
