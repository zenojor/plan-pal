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

/**
 * Agent 服务层 - 编排规划流程
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private final ReActEngine engine;
    private final ObjectMapper objectMapper;

    public AgentService(ReActEngine engine, ObjectMapper objectMapper) {
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步规划
     */
    public PlanResponse plan(PlanRequest request) {
        log.info("[AgentService] 收到规划请求: userId={}, prompt={}", request.userId(), request.prompt());
        return engine.executePlan(request);
    }

    /**
     * SSE 流式规划 - 实时推送 ReAct 步骤
     *
     * 使用 Spring MVC SseEmitter 实现服务端推送。
     * ReAct 循环在异步线程中执行，通过 SseEmitter 逐步发送
     * THOUGHT → ACTION → OBSERVATION → FINISH 事件。
     */
    public SseEmitter planStream(PlanRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                send(emitter, new SseEvent("START", 0, "开始规划...", null));

                PlanResponse response = engine.executePlan(request);

                for (var t : response.trace()) {
                    send(emitter, new SseEvent(t.type(), t.step(), t.content(), null));
                }

                send(emitter, new SseEvent("FINISH", response.trace().size() + 1,
                        response.summary(), response.timeline()));

                emitter.complete();
            } catch (Exception e) {
                log.error("[AgentService] 规划失败", e);
                try {
                    send(emitter, new SseEvent("ERROR", 0, e.getMessage(), null));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.warn("[SSE] 发送事件失败: {}", e.getMessage());
        }
    }
}
