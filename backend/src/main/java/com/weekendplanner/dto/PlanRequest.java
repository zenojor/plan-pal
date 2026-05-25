package com.weekendplanner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户规划请求
 */
public record PlanRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,

        @NotBlank(message = "prompt 不能为空")
        @Size(max = 500, message = "输入内容不能超过500字")
        String prompt,

        String planId
) {
    public PlanRequest(String userId, String prompt) {
        this(userId, prompt, null);
    }
}
