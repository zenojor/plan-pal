package com.weekendplanner.provider;

import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.mock.MockPoiDatabase;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SandboxPoiProvider implements PoiProvider {

    private final MockPoiDatabase database;

    public SandboxPoiProvider(MockPoiDatabase database) {
        this.database = database;
    }

    @Override
    public List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm) {
        return database.searchByCategory(category, tags, radiusKm);
    }

    @Override
    public Optional<PoiDto> findById(String poiId) {
        return database.findById(poiId);
    }

    @Override
    public List<PoiDto> searchNearby(String poiId, String category, int radiusKm) {
        return database.searchNearby(poiId, category, radiusKm);
    }
}
