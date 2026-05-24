package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.mock.MockMovieDatabase;
import com.weekendplanner.mock.MockPoiDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电影排片检索工具
 *
 * 查询指定电影院的当日排片信息。
 */
@Component
public class MovieSearchTool {

    private static final Logger log = LoggerFactory.getLogger(MovieSearchTool.class);
    private final MockMovieDatabase movieDb;
    private final MockPoiDatabase poiDb;
    private final ObjectMapper objectMapper;

    public MovieSearchTool(MockPoiDatabase poiDb, ObjectMapper objectMapper) {
        this.movieDb = new MockMovieDatabase();
        this.poiDb = poiDb;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "searchMovies";
    }

    public String getDescription() {
        return "查询电影院排片信息。参数: cinemaId(影院POI ID,如P007/P019/P020), genre(类型过滤,如动画/喜剧), keyword(片名关键字), afterTime(只看该时间之后的场次,如14:00)";
    }

    public String execute(String parametersJson) {
        try {
            JsonNode params = objectMapper.readTree(parametersJson);
            String cinemaId = params.has("cinemaId") ? params.get("cinemaId").asText() : null;
            String genre = params.has("genre") ? params.get("genre").asText() : null;
            String keyword = params.has("keyword") ? params.get("keyword").asText() : null;
            String afterTime = params.has("afterTime") ? params.get("afterTime").asText() : null;

            List<MockMovieDatabase.MovieListing> results;
            if (cinemaId != null && !cinemaId.isBlank()) {
                results = movieDb.searchByCinemaAndTime(cinemaId, afterTime);
                if (genre != null && !genre.isBlank()) {
                    results = results.stream()
                            .filter(m -> m.genre().contains(genre)).toList();
                }
            } else {
                results = movieDb.search(genre, keyword);
            }

            // 附加影院名称
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (var m : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("movieId", m.movieId());
                item.put("title", m.title());
                item.put("genre", m.genre());
                item.put("durationMinutes", m.durationMinutes());
                item.put("rating", m.rating());
                item.put("cinemaId", m.cinemaId());
                item.put("showtimes", m.showtimes());
                item.put("pricePerTicket", m.pricePerTicket());
                poiDb.findById(m.cinemaId()).ifPresent(poi ->
                        item.put("cinemaName", poi.name()));
                enriched.add(item);
            }

            log.info("[searchMovies] cinemaId={}, genre={}, keyword={}, afterTime={} → {} movies",
                    cinemaId, genre, keyword, afterTime, enriched.size());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", enriched);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[searchMovies] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误\"}";
        }
    }
}
