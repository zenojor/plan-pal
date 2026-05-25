package com.weekendplanner.provider;

import java.util.List;

public interface OrderExecutionProvider {

    ExecutionResult execute(List<String> orderIds, String contactToken);

    record ExecutionResult(
            String orderGroupId,
            String notifiedContact,
            String status,
            String message,
            String provider,
            String traceId,
            String errorCode
    ) {}
}
