package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.CheckRequest;
import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.provider.AvailabilityProvider;
import com.weekendplanner.provider.SandboxAvailabilityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RestaurantReservationTool {

    private static final Logger log = LoggerFactory.getLogger(RestaurantReservationTool.class);
    private final AvailabilityProvider availabilityProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public RestaurantReservationTool(AvailabilityProvider availabilityProvider, ObjectMapper objectMapper) {
        this.availabilityProvider = availabilityProvider;
        this.objectMapper = objectMapper;
    }

    public RestaurantReservationTool(PoiProvider poiProvider, ObjectMapper objectMapper) {
        this(new SandboxAvailabilityProvider(poiProvider), objectMapper);
    }

    public String getToolName() {
        return "checkAvailability";
    }

    public String getDescription() {
        return "Check real-time availability for a POI. Params: poiId, targetTime, headcount";
    }

    public String execute(String parametersJson) {
        try {
            CheckRequest request = objectMapper.readValue(parametersJson, CheckRequest.class);
            if (request.poiId() == null || request.poiId().isBlank()) {
                return "{\"error\":\"missing poiId\"}";
            }

            String targetTime = request.targetTime() != null ? request.targetTime() : "14:00";
            int headcount = request.headcount() > 0 ? request.headcount() : 3;
            CheckResponse response = availabilityProvider.checkAvailability(request.poiId(), targetTime, headcount);

            log.info("[checkAvailability] poiId={}, time={}, headcount={} -> status={}, queue={}min",
                    request.poiId(), targetTime, headcount, response.status(), response.queueTimeMinutes());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[checkAvailability] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }
}
