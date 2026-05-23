package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.engine.ReActEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private final ReActEngine engine;
    private final ObjectMapper objectMapper;

    public AgentService(ReActEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    /** 同步规划 */
    public PlanResponse plan(PlanRequest request) {
        log.info("[AgentService] plan userId={}", request.userId());
        return engine.executePlan(request);
    }

    /**
     * SSE 流式规划 — 真正的逐步骤实时推送
     *
     * 引擎在每一步完成时通过 Consumer 回调发射事件，
     * SseEmitter 立即发送到前端，不等待整个循环结束。
     */
    public SseEmitter planStream(PlanRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                engine.executePlanStreaming(request, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type())
                                .data(objectMapper.writeValueAsString(event)));
                    } catch (IOException e) {
                        log.warn("[SSE] 发送失败: {}", e.getMessage());
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE] 规划异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("ERROR")
                            .data("{\"type\":\"ERROR\",\"step\":0,\"content\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
