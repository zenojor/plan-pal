package com.weekendplanner.provider;

import com.weekendplanner.dto.PoiDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Primary
@Component
public class SelectedPoiProvider implements PoiProvider {

    private final PoiProvider amapPoiProvider;
    private final PoiProvider sandboxPoiProvider;
    private final String providerMode;

    public SelectedPoiProvider(@Qualifier("amapPoiProvider") PoiProvider amapPoiProvider,
                               @Qualifier("sandboxPoiProvider") PoiProvider sandboxPoiProvider,
                               @Value("${planner.poi.provider:sandbox}") String providerMode) {
        this.amapPoiProvider = amapPoiProvider;
        this.sandboxPoiProvider = sandboxPoiProvider;
        this.providerMode = providerMode;
    }

    @Override
    public List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm) {
        return active().searchByCategory(category, tags, radiusKm);
    }

    @Override
    public Optional<PoiDto> findById(String poiId) {
        return active().findById(poiId);
    }

    @Override
    public List<PoiDto> searchNearby(String poiId, String category, int radiusKm) {
        return active().searchNearby(poiId, category, radiusKm);
    }

    private PoiProvider active() {
        return "sandbox".equalsIgnoreCase(providerMode) ? sandboxPoiProvider : amapPoiProvider;
    }
}
