package com.weekendplanner.dto;

import java.util.List;

/**
 * 规划响应
 */
public record PlanResponse(
        String planId,
        String userId,
        String status,           // SUCCESS / DEGRADED / FAILED
        String summary,          // 最终方案文本
        List<PlanStep> timeline, // 时间线
        List<ReActTrace> trace,  // ReAct 思考链
        String orderGroupId,     // Mock 订单流水号
        String notificationText, // 通知消息文本
        String degradationNote   // 降级提示(如有)
) {}
