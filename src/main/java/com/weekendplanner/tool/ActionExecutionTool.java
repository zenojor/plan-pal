package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ExecuteOrderRequest;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.provider.OrderExecutionProvider;
import com.weekendplanner.provider.SandboxNotificationProvider;
import com.weekendplanner.provider.SandboxOrderExecutionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ActionExecutionTool {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionTool.class);
    private final OrderExecutionProvider orderExecutionProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public ActionExecutionTool(OrderExecutionProvider orderExecutionProvider, ObjectMapper objectMapper) {
        this.orderExecutionProvider = orderExecutionProvider;
        this.objectMapper = objectMapper;
    }

    public ActionExecutionTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this(new SandboxOrderExecutionProvider(orderSystem, new SandboxNotificationProvider(orderSystem)), objectMapper);
    }

    public String getToolName() {
        return "executeOrderAndNotify";
    }

    public String getDescription() {
        return "Execute prepared orders and send notifications. Params: orderIds, contactToken";
    }

    public String execute(String parametersJson) {
        try {
            ExecuteOrderRequest request = objectMapper.readValue(parametersJson, ExecuteOrderRequest.class);
            OrderExecutionProvider.ExecutionResult result = orderExecutionProvider.execute(
                    request.orderIds(), request.contactToken());
            ExecuteOrderResponse response = new ExecuteOrderResponse(
                    result.orderGroupId(), result.notifiedContact(), result.status(), result.message(),
                    result.provider(), result.traceId(), result.errorCode());
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[executeOrder] invalid params: {}", e.getMessage());
            return "{\"error\":\"invalid parameters\"}";
        }
    }
}
