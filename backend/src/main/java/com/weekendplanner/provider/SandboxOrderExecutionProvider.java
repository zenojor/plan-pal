package com.weekendplanner.provider;

import com.weekendplanner.mock.MockOrderSystem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SandboxOrderExecutionProvider implements OrderExecutionProvider {

    private final MockOrderSystem orderSystem;
    private final NotificationProvider notificationProvider;

    public SandboxOrderExecutionProvider(MockOrderSystem orderSystem, NotificationProvider notificationProvider) {
        this.orderSystem = orderSystem;
        this.notificationProvider = notificationProvider;
    }

    @Override
    public ExecutionResult execute(List<String> orderIds, String contactToken) {
        String traceId = TraceIds.traceId("exec");
        String orderGroupId = orderSystem.createOrderGroup();
        List<String> lockedIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String orderId : orderIds) {
            if (orderSystem.tryLock(orderId)) {
                lockedIds.add(orderId);
            } else {
                failedIds.add(orderId);
            }
        }

        String status;
        String message;
        if (!failedIds.isEmpty()) {
            for (String id : lockedIds) {
                orderSystem.rollback(id);
            }
            status = "PARTIAL_FAILED";
            message = "Failed to lock: " + String.join(",", failedIds);
        } else {
            for (String id : lockedIds) {
                orderSystem.commit(id);
            }
            status = "DISPATCHED";
            message = "All orders confirmed";
        }

        notificationProvider.sendNotification(contactToken, "Order group " + orderGroupId + " status: " + status);
        return new ExecutionResult(orderGroupId, contactToken, status, message, "sandbox", traceId, "");
    }
}
