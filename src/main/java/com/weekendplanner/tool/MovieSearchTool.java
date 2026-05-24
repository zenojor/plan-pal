package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.provider.MovieListingProvider;
import com.weekendplanner.provider.PoiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MovieSearchTool {

    private static final Logger log = LoggerFactory.getLogger(MovieSearchTool.class);
    private final MovieListingProvider movieDb;
    private final PoiProvider poiDb;
    private final ObjectMapper objectMapper;

    public MovieSearchTool(MovieListingProvider movieDb, PoiProvider poiDb, ObjectMapper objectMapper) {
        this.movieDb = movieDb;
        this.poiDb = poiDb;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "searchMovies";
    }

    public String getDescription() {
        return "Search movie listings. Params: cinemaId, genre, keyword, afterTime";
    }

    public String execute(String parametersJson) {
        try {
            JsonNode params = objectMapper.readTree(parametersJson);
            String cinemaId = params.has("cinemaId") ? params.get("cinemaId").asText() : null;
            String genre = params.has("genre") ? params.get("genre").asText() : null;
            String keyword = params.has("keyword") ? params.get("keyword").asText() : null;
            String afterTime = params.has("afterTime") ? params.get("afterTime").asText() : null;

            List<MovieListingProvider.MovieListing> results;
            if (cinemaId != null && !cinemaId.isBlank()) {
                results = movieDb.searchByCinemaAndTime(cinemaId, afterTime);
                if (genre != null && !genre.isBlank()) {
                    results = results.stream().filter(m -> m.genre().contains(genre)).toList();
                }
            } else {
                results = movieDb.search(genre, keyword);
            }

            List<Map<String, Object>> enriched = new ArrayList<>();
            for (var listing : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("movieId", listing.movieId());
                item.put("title", listing.title());
                item.put("genre", listing.genre());
                item.put("durationMinutes", listing.durationMinutes());
                item.put("rating", listing.rating());
                item.put("cinemaId", listing.cinemaId());
                item.put("showtimes", listing.showtimes());
                item.put("pricePerTicket", listing.pricePerTicket());
                poiDb.findById(listing.cinemaId()).ifPresent(poi -> item.put("cinemaName", poi.name()));
                enriched.add(item);
            }

            log.info("[searchMovies] cinemaId={}, genre={}, keyword={}, afterTime={} -> {} movies",
                    cinemaId, genre, keyword, afterTime, enriched.size());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", enriched);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[searchMovies] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }
}
