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
        String degradationNote,  // 降级提示(如有)
        PlanIntent intent,
        List<OrderIntent> orderIntents,
        String executionStatus   // PENDING_CONFIRMATION / EXECUTED / FAILED
) {
    public PlanResponse(String planId,
                        String userId,
                        String status,
                        String summary,
                        List<PlanStep> timeline,
                        List<ReActTrace> trace,
                        String orderGroupId,
                        String notificationText,
                        String degradationNote) {
        this(planId, userId, status, summary, timeline, trace, orderGroupId, notificationText,
                degradationNote, null, List.of(), status);
    }
}
