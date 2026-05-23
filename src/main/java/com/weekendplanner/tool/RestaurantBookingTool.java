package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ReserveRestaurantRequest;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.mock.MockOrderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 餐厅预约工具
 *
 * 将已通过可用性检查的餐饮 POI 转成可执行预约单，供一键执行网关统一提交。
 */
@Component
public class RestaurantBookingTool {

    private static final Logger log = LoggerFactory.getLogger(RestaurantBookingTool.class);
    private final MockOrderSystem orderSystem;
    private final ObjectMapper objectMapper;

    public RestaurantBookingTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this.orderSystem = orderSystem;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "reserveRestaurant";
    }

    public String getDescription() {
        return "预约指定餐厅。参数: poiId(POI标识), headcount(人数), targetTime(目标时间如16:30)";
    }

    public String execute(String parametersJson) {
        try {
            ReserveRestaurantRequest request = objectMapper.readValue(parametersJson, ReserveRestaurantRequest.class);
            String targetTime = request.targetTime() != null ? request.targetTime() : "16:30";
            int headcount = request.headcount() > 0 ? request.headcount() : 3;

            MockOrderSystem.ReservationResult result = orderSystem.reserve(request.poiId(), headcount, targetTime);
            ReserveRestaurantResponse response = new ReserveRestaurantResponse(
                    result.reservationId(), result.success(), result.message());

            log.info("[reserveRestaurant] poiId={}, headcount={}, time={} → reservationId={}",
                    request.poiId(), headcount, targetTime, result.reservationId());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[reserveRestaurant] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误，需要 poiId, headcount, targetTime\"}";
        }
    }
}
