package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.BookTicketRequest;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.mock.MockOrderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 票务与展会核销工具
 *
 * 模拟门票/展览票购买流程。
 */
@Component
public class TicketingTool {

    private static final Logger log = LoggerFactory.getLogger(TicketingTool.class);
    private final MockOrderSystem orderSystem;
    private final ObjectMapper objectMapper;

    public TicketingTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this.orderSystem = orderSystem;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "bookTickets";
    }

    public String getDescription() {
        return "预订指定 POI 的门票/入场券。参数: poiId(POI标识), num(数量), sessionTime(场次时间)";
    }

    /**
     * 执行购票
     */
    public String execute(String parametersJson) {
        try {
            BookTicketRequest request = objectMapper.readValue(parametersJson, BookTicketRequest.class);

            MockOrderSystem.TicketResult result = orderSystem.buyTicket(
                    request.poiId(), request.num(), request.sessionTime());

            TicketResponse response = new TicketResponse(
                    result.ticketId(), result.success(), result.totalPrice(), result.message());

            log.info("[bookTickets] poiId={}, num={}, session={} → ticketId={}, price={}",
                    request.poiId(), request.num(), request.sessionTime(),
                    result.ticketId(), result.totalPrice());

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[bookTickets] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误\"}";
        }
    }
}
