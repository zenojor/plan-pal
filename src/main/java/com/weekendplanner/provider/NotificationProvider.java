package com.weekendplanner.provider;

public interface NotificationProvider {

    NotificationResult sendNotification(String contactToken, String message);

    record NotificationResult(
            String notificationId,
            String provider,
            String traceId,
            String status,
            String errorCode,
            String message
    ) {}
}
