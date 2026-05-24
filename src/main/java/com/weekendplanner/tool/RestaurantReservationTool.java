package com.weekendplanner.tool;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.CheckRequest;
import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.mock.MockPoiDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 实时状态与排队查验工具
 *
 * 核心哨兵: 查询 POI 的实时排队/余票状态。
 * P002 (绿意轻食馆) 固定返回高延迟排队状态，用于触发 Agent 重规划。
 */
@Component
public class RestaurantReservationTool {

    private static final Logger log = LoggerFactory.getLogger(RestaurantReservationTool.class);
    private final MockPoiDatabase database;
    private final ObjectMapper objectMapper;

    public RestaurantReservationTool(MockPoiDatabase database, ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "checkAvailability";
    }

    public String getDescription() {
        return "查询指定 POI 的实时排队/可用性状态。参数: poiId(POI标识), targetTime(目标时间如14:00), headcount(人数)";
    }

    /**
     * 执行排队/可用性查验
     *
     * 按照设计文档规范:
     * - P002 固定返回高延迟(90分钟)，触发重规划
     * - 其他 POI 根据 ID 哈希伪随机模拟状态
     */
    public String execute(String parametersJson) {
        try {
            CheckRequest request = objectMapper.readValue(parametersJson, CheckRequest.class);

            if (request.poiId() == null || request.poiId().isBlank()) {
                return "{\"error\": \"缺少 poiId 参数，请从 searchNearby 结果中指定一个 POI ID\"}";
            }

            String targetTime = request.targetTime() != null ? request.targetTime() : "14:00";
            int headcount = request.headcount() > 0 ? request.headcount() : 3;

            CheckResponse response = checkStatus(request.poiId(), targetTime, headcount);

            log.info("[checkAvailability] poiId={}, time={}, headcount={} → status={}, queue={}min",
                    request.poiId(), targetTime, headcount,
                    response.status(), response.queueTimeMinutes());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[checkAvailability] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误，需要 poiId, targetTime, headcount\"}";
        }
    }

    private CheckResponse checkStatus(String poiId, String targetTime, int headcount) {
        // P002 是关键扰动点 —— 固定返回长排队
        if ("P002".equalsIgnoreCase(poiId)) {
            return new CheckResponse(poiId, "QUEUED", 90, true);
        }

        // P008 固定 AVAILABLE
        if ("P008".equalsIgnoreCase(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 0, false);
        }

        // P005 / P013 / P014 (普通中餐) 固定 AVAILABLE
        if (List.of("P005", "P013", "P014").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 5, false);
        }

        // P003 / P004 / P007 / P018 (社交活动) 固定低排队
        if (List.of("P003", "P004", "P007", "P018").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 8, false);
        }

        // 电影院 / 酒店 / 购物 固定 AVAILABLE
        if (List.of("P030", "P031", "H001", "H002", "H003", "S001", "S002", "S003").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 5, false);
        }

        // 其他 POI: 基于 poiId hash 伪随机
        Optional<PoiDto> poiOpt = database.findById(poiId);
        if (poiOpt.isEmpty()) {
            return new CheckResponse(poiId, "UNKNOWN", 0, false);
        }

        int hash = Math.abs(poiId.hashCode());
        int queueTime = hash % 60;  // 0-59 分钟
        String status = queueTime > 30 ? "QUEUED" : "AVAILABLE";

        return new CheckResponse(poiId, status, queueTime, queueTime > 20);
    }
}
