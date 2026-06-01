package com.weekendplanner.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.mock.MockOrderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RideHailingTool {

    private static final Logger log = LoggerFactory.getLogger(RideHailingTool.class);

    private final MockOrderSystem orderSystem;
    private final ObjectMapper objectMapper;

    public RideHailingTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this.orderSystem = orderSystem;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "hailRide";
    }

    public String getDescription() {
        return "Create a mock ride-hailing order. Params: fromPoiName, toPoiName, distanceKm, targetTime";
    }

    public String execute(String parametersJson) {
        try {
            JsonNode node = objectMapper.readTree(parametersJson);
            MockOrderSystem.RideResult result = orderSystem.hailRide(
                    text(node, "fromPoiName", "起点"),
                    text(node, "toPoiName", "终点"),
                    node.path("distanceKm").asDouble(0),
                    text(node, "targetTime", ""));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[hailRide] invalid params: {}", e.getMessage());
            return "{\"success\":false,\"message\":\"invalid parameters\"}";
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value;
    }
}
