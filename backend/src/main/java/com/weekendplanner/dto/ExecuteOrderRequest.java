package com.weekendplanner.dto;

import java.util.List;

/**
 * 一键执行订单请求
 */
public record ExecuteOrderRequest(
        List<String> orderIds,
        String contactToken
) {}
