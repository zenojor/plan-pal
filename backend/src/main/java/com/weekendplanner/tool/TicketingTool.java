package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.BookTicketRequest;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.provider.SandboxTicketProvider;
import com.weekendplanner.provider.TicketProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TicketingTool {

    private static final Logger log = LoggerFactory.getLogger(TicketingTool.class);
    private final TicketProvider ticketProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public TicketingTool(TicketProvider ticketProvider, ObjectMapper objectMapper) {
        this.ticketProvider = ticketProvider;
        this.objectMapper = objectMapper;
    }

    public TicketingTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this(new SandboxTicketProvider(orderSystem), objectMapper);
    }

    public String getToolName() {
        return "bookTickets";
    }

    public String getDescription() {
        return "Book tickets for a POI. Params: poiId, num, sessionTime";
    }

    public String execute(String parametersJson) {
        try {
            BookTicketRequest request = objectMapper.readValue(parametersJson, BookTicketRequest.class);
            TicketProvider.TicketResult result = ticketProvider.bookTickets(
                    request.poiId(), request.num(), request.sessionTime(),
                    "ticket-" + request.poiId() + "-" + request.sessionTime());

            TicketResponse response = new TicketResponse(
                    result.ticketId(), result.success(), result.totalPrice(), result.message(), result.provider(),
                    result.traceId(), result.errorCode(), result.externalOrderId(), result.idempotencyKey());

            log.info("[bookTickets] poiId={}, num={}, session={} -> ticketId={}, price={}",
                    request.poiId(), request.num(), request.sessionTime(), result.ticketId(), result.totalPrice());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[bookTickets] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }
}
