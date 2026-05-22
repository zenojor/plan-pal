package com.weekendplanner.dto;

/**
 * 一键执行订单响应
 */
public record ExecuteOrderResponse(
        String orderGroupId,
        String notifiedContact,
        String status,           // DISPATCHED / PARTIAL_FAILED / ROLLED_BACK
        String message
) {}
