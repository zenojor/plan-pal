package com.weekendplanner.dto;

import java.util.List;

/**
 * 用户确认方案后提交当前排序后的时间线。
 */
public record ConfirmPlanRequest(
        String planId,
        String userId,
        List<PlanStep> timeline,
        int headcount,
        String notificationText
) {}
