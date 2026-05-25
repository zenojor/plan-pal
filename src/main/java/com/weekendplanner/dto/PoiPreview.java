package com.weekendplanner.dto;

import java.util.List;

public record PoiPreview(
        String poiId,
        String name,
        String category,
        double distanceKm,
        List<String> tags,
        String address,
        String businessHours,
        String telephone,
        String source,
        String placeholderImage
) {
    public PoiPreview {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
