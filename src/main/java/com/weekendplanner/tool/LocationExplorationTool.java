package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SearchRequest;
import com.weekendplanner.dto.SearchResponse;
import com.weekendplanner.mock.MockPoiDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 周边空间检索工具
 *
 * 根据场景约束检索特定范围内的 POI。
 * Spring AI 通过 @Tool 注解将契约暴露给 LLM。
 */
@Component
public class LocationExplorationTool {

    private static final Logger log = LoggerFactory.getLogger(LocationExplorationTool.class);
    private final MockPoiDatabase database;
    private final ObjectMapper objectMapper;

    public LocationExplorationTool(MockPoiDatabase database, ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "searchNearby";
    }

    public String getDescription() {
        return "搜索指定类别和标签的周边 POI。参数: category(类别: ACTIVITY/RESTAURANT), tags(标签列表), radiusKm(搜索半径km)";
    }

    /**
     * 执行搜索并返回 JSON 字符串 (Observation)
     * 缺失参数自动填默认值: radiusKm=3
     */
    public String execute(String parametersJson) {
        try {
            SearchRequest request = objectMapper.readValue(parametersJson, SearchRequest.class);

            // 默认值保护: 空参数时也能搜到东西
            String category = request.category() != null && !request.category().isBlank()
                    ? request.category() : null;
            List<String> tags = request.tags() != null && !request.tags().isEmpty()
                    ? request.tags() : null;
            int radius = request.radiusKm() > 0 ? request.radiusKm() : 3;

            List<PoiDto> results = database.searchByCategory(category, tags, radius);

            log.info("[searchNearby] category={}, tags={}, radius={}km → {} POIs",
                    category, tags, radius, results.size());

            return objectMapper.writeValueAsString(new SearchResponse(results));
        } catch (JsonProcessingException e) {
            log.error("[searchNearby] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误\"}";
        }
    }

    /**
     * 在指定 POI 周围搜索同类别替代方案（重规划用）
     */
    public String searchAlternative(String poiId, String category, int radiusKm) {
        List<PoiDto> results = database.searchNearby(poiId, category, radiusKm);
        log.info("[searchNearby-alternative] origin={}, radius={}km → {} 替代POI",
                poiId, radiusKm, results.size());
        try {
            return objectMapper.writeValueAsString(new SearchResponse(results));
        } catch (JsonProcessingException e) {
            return "{\"error\": \"序列化失败\"}";
        }
    }
}
