package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ReserveRestaurantRequest;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.provider.ReservationProvider;
import com.weekendplanner.provider.SandboxReservationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RestaurantBookingTool {

    private static final Logger log = LoggerFactory.getLogger(RestaurantBookingTool.class);
    private final ReservationProvider reservationProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public RestaurantBookingTool(ReservationProvider reservationProvider, ObjectMapper objectMapper) {
        this.reservationProvider = reservationProvider;
        this.objectMapper = objectMapper;
    }

    public RestaurantBookingTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this(new SandboxReservationProvider(orderSystem), objectMapper);
    }

    public String getToolName() {
        return "reserveRestaurant";
    }

    public String getDescription() {
        return "Reserve a restaurant POI. Params: poiId, headcount, targetTime";
    }

    public String execute(String parametersJson) {
        try {
            ReserveRestaurantRequest request = objectMapper.readValue(parametersJson, ReserveRestaurantRequest.class);
            String targetTime = request.targetTime() != null ? request.targetTime() : "16:30";
            int headcount = request.headcount() > 0 ? request.headcount() : 3;

            ReservationProvider.ReservationResult result = reservationProvider.reserve(
                    request.poiId(), headcount, targetTime, "reserve-" + request.poiId() + "-" + targetTime);
            ReserveRestaurantResponse response = new ReserveRestaurantResponse(
                    result.reservationId(), result.success(), result.message(), result.provider(), result.traceId(),
                    result.errorCode(), result.externalOrderId(), result.idempotencyKey());

            log.info("[reserveRestaurant] poiId={}, headcount={}, time={} -> reservationId={}",
                    request.poiId(), headcount, targetTime, result.reservationId());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[reserveRestaurant] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }
}
