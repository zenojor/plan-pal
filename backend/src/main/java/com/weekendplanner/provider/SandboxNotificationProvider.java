package com.weekendplanner.provider;

import com.weekendplanner.mock.MockOrderSystem;
import org.springframework.stereotype.Component;

@Component
public class SandboxNotificationProvider implements NotificationProvider {

    private final MockOrderSystem orderSystem;

    public SandboxNotificationProvider(MockOrderSystem orderSystem) {
        this.orderSystem = orderSystem;
    }

    @Override
    public NotificationResult sendNotification(String contactToken, String message) {
        String traceId = TraceIds.traceId("notify");
        String notificationId = orderSystem.sendNotification(contactToken, message);
        return new NotificationResult(notificationId, "sandbox", traceId, "SENT", "", message);
    }
}
