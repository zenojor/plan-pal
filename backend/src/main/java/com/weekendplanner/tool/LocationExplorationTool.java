package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SearchRequest;
import com.weekendplanner.dto.SearchResponse;
import com.weekendplanner.provider.PoiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocationExplorationTool {

    private static final Logger log = LoggerFactory.getLogger(LocationExplorationTool.class);
    private final PoiProvider poiProvider;
    private final ObjectMapper objectMapper;

    public LocationExplorationTool(PoiProvider poiProvider, ObjectMapper objectMapper) {
        this.poiProvider = poiProvider;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "searchNearby";
    }

    public String getDescription() {
        return "Search nearby POIs by category and tags. Params: category, tags, radiusKm";
    }

    public String execute(String parametersJson) {
        try {
            SearchRequest request = objectMapper.readValue(parametersJson, SearchRequest.class);
            String category = request.category() != null && !request.category().isBlank() ? request.category() : null;
            List<String> tags = request.tags() != null && !request.tags().isEmpty() ? request.tags() : null;
            int radius = request.radiusKm() > 0 ? request.radiusKm() : 3;

            List<PoiDto> results = poiProvider.searchByCategory(category, tags, radius);
            log.info("[searchNearby] category={}, tags={}, radius={}km -> {} POIs", category, tags, radius, results.size());

            return objectMapper.writeValueAsString(new SearchResponse(results));
        } catch (JsonProcessingException e) {
            log.error("[searchNearby] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }

    public String searchAlternative(String poiId, String category, int radiusKm) {
        List<PoiDto> results = poiProvider.searchNearby(poiId, category, radiusKm);
        log.info("[searchNearby-alternative] origin={}, radius={}km -> {} POIs", poiId, radiusKm, results.size());
        try {
            return objectMapper.writeValueAsString(new SearchResponse(results));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
