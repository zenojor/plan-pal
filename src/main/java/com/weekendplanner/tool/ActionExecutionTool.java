package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ExecuteOrderRequest;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.mock.MockOrderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键网关下单与通知工具
 *
 * 串联执行所有预订动作，并发送通知消息。
 * 支持类2PC事务: tryLock → commit / rollback
 */
@Component
public class ActionExecutionTool {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionTool.class);
    private final MockOrderSystem orderSystem;
    private final ObjectMapper objectMapper;

    public ActionExecutionTool(MockOrderSystem orderSystem, ObjectMapper objectMapper) {
        this.orderSystem = orderSystem;
        this.objectMapper = objectMapper;
    }

    public String getToolName() {
        return "executeOrderAndNotify";
    }

    public String getDescription() {
        return "串联执行所有预订订单并发送通知。参数: orderIds(订单ID列表), contactToken(联系人标识如\"小张\")";
    }

    /**
     * 2PC 事务执行
     */
    public String execute(String parametersJson) {
        try {
            ExecuteOrderRequest request = objectMapper.readValue(parametersJson, ExecuteOrderRequest.class);

            String orderGroupId = orderSystem.createOrderGroup();
            List<String> lockedIds = new ArrayList<>();
            List<String> failedIds = new ArrayList<>();

            // Phase 1: tryLock all
            for (String orderId : request.orderIds()) {
                if (orderSystem.tryLock(orderId)) {
                    lockedIds.add(orderId);
                } else {
                    failedIds.add(orderId);
                }
            }

            // Phase 2: commit or rollback based on result
            String status;
            String message;

            if (!failedIds.isEmpty()) {
                // 有失败 → 全部回滚
                for (String id : lockedIds) {
                    orderSystem.rollback(id);
                }
                status = "PARTIAL_FAILED";
                message = "部分订单锁定失败(已回滚): " + String.join(",", failedIds);
                log.warn("[executeOrder] 2PC 回滚: locked={}, failed={}", lockedIds, failedIds);
            } else {
                // 全部成功 → 提交
                for (String id : lockedIds) {
                    orderSystem.commit(id);
                }
                status = "DISPATCHED";
                message = "所有订单已确认执行";
                log.info("[executeOrder] 2PC 提交: orders={}", lockedIds);
            }

            // 发送通知
            String notifyResult = orderSystem.sendNotification(
                    request.contactToken(),
                    "订单组 " + orderGroupId + " 已处理完成, 状态: " + status);

            ExecuteOrderResponse response = new ExecuteOrderResponse(
                    orderGroupId, request.contactToken(), status, message + " | 通知: " + notifyResult);

            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("[executeOrder] 参数解析失败: {}", e.getMessage());
            return "{\"error\": \"参数格式错误\"}";
        }
    }
}
