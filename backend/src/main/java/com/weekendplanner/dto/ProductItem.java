package com.weekendplanner.dto;

import java.util.List;

public record ProductItem(
        String productId,
        String productName,
        String category,
        String merchantPoiId,
        String merchantName,
        double priceCny,
        double rating,
        int trendingScore,
        List<String> tags,
        String reason,
        boolean available
) {
    public ProductItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
