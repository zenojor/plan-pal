package com.weekendplanner.controller;

import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.service.AgentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent API 控制器
 *
 * 对外暴露 RESTful 接口:
 * - POST /api/v1/agent/plan (同步)
 * - GET  /api/v1/agent/plan/stream (SSE 流式)
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 同步规划接口
     */
    @PostMapping("/plan")
    public ResponseEntity<PlanResponse> plan(@Valid @RequestBody PlanRequest request) {
        log.info("[API] POST /api/v1/agent/plan userId={}", request.userId());
        PlanResponse response = agentService.plan(request);
        return ResponseEntity.ok(response);
    }

    /**
     * SSE 流式规划接口
     *
     * 前端可通过 EventSource 订阅实时思考链:
     *   const es = new EventSource('/api/v1/agent/plan/stream?userId=U001&prompt=...');
     *   es.addEventListener('THOUGHT', e => console.log(JSON.parse(e.data)));
     *   es.addEventListener('FINISH',  e => console.log('Done:', JSON.parse(e.data)));
     */
    @GetMapping(path = "/plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter planStream(
            @RequestParam String userId,
            @RequestParam String prompt) {
        log.info("[API] GET /api/v1/agent/plan/stream userId={}", userId);
        PlanRequest request = new PlanRequest(userId, prompt);
        return agentService.planStream(request);
    }

    /**
     * 用户确认方案后执行 mock 下单/预约/通知。
     */
    @PostMapping("/plan/{planId}/confirm")
    public ResponseEntity<ConfirmPlanResponse> confirmPlan(
            @PathVariable String planId,
            @Valid @RequestBody ConfirmPlanRequest request) {
        log.info("[API] POST /api/v1/agent/plan/{}/confirm userId={}", planId, request.userId());
        return ResponseEntity.ok(agentService.confirmPlan(planId, request));
    }

    /**
     * 对话式规划微调流式接口 (SSE)
     */
    @GetMapping(path = "/plan/{planId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter planChatStream(
            @PathVariable String planId,
            @RequestParam String userId,
            @RequestParam String prompt,
            @RequestParam(required = false) String segmentId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String clientActionId,
            @RequestParam(required = false) String patch) {
        log.info("[API] GET /api/v1/agent/plan/{}/chat/stream planId={}, userId={}, prompt={}", planId, planId, userId, prompt);
        return agentService.planChatStream(planId, userId, prompt, segmentId, source, clientActionId, patch);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent is running");
    }
}
