package com.weekendplanner.provider;

import com.weekendplanner.dto.PoiDto;

import java.util.List;
import java.util.Optional;

public interface PoiProvider {

    List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm);

    Optional<PoiDto> findById(String poiId);

    List<PoiDto> searchNearby(String poiId, String category, int radiusKm);
}
