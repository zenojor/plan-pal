package com.weekendplanner.engine.graph;

import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.interaction.InteractionDecision;
import com.weekendplanner.engine.routing.InitialRouteCommand;

import java.util.List;

public record PlanGraphState(
        String threadId,
        String userId,
        String planId,
        String userTurn,
        String operation,
        PlanRequest planRequest,
        String segmentId,
        String source,
        String clientActionId,
        String patchPayload,
        InitialRouteCommand initialRoute,
        AgentContext agentContext,
        ContextPack contextPack,
        InteractionDecision interactionDecision,
        PlanPatch directPatch,
        PlanDelta delta,
        PlanResponse response,
        String nextNode,
        Throwable failure,
        List<PlanGraphEvents.PlanGraphEvent> events
) {
    public PlanGraphState {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public PlanGraphState(String threadId,
                          String userId,
                          String planId,
                          String userTurn,
                          String mode,
                          PlanRequest planRequest,
                          ContextPack contextPack,
                          PlanResponse response,
                          String nextNode,
                          List<PlanGraphEvents.PlanGraphEvent> events) {
        this(threadId, userId, planId, userTurn, mode, planRequest, null, null, null,
                null, null, null, contextPack, null, null, null, response, nextNode, null, events);
    }

    public static PlanGraphState create(String threadId, PlanRequest request, String operation) {
        return new PlanGraphState(threadId, request.userId(), request.planId(), request.prompt(), operation,
                request, null, null, null, null, null, null, null, null, null, null, null,
                PlanGraphNodes.UNDERSTAND_INITIAL, null, List.of());
    }

    public static PlanGraphState chat(String threadId,
                                      String planId,
                                      String userId,
                                      String prompt,
                                      String segmentId,
                                      String source,
                                      String clientActionId,
                                      String patchPayload) {
        return new PlanGraphState(threadId, userId, planId, prompt, "chat", null, segmentId, source,
                clientActionId, patchPayload, null, null, null, null, null, null, null,
                PlanGraphNodes.ASSEMBLE_CONTEXT, null, List.of());
    }

    public PlanGraphState withNext(String nextNode) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, agentContext, contextPack,
                interactionDecision, directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withInitialRoute(InitialRouteCommand route) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, route, agentContext, contextPack, interactionDecision,
                directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withAgentContext(AgentContext context) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, context, contextPack, interactionDecision,
                directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withInteractionDecision(InteractionDecision decision) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, agentContext, contextPack, decision,
                directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withDirectPatch(PlanPatch patch) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, agentContext, contextPack,
                interactionDecision, patch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withDelta(PlanDelta delta) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, agentContext, contextPack,
                interactionDecision, directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withResponse(PlanResponse response) {
        String resolvedPlanId = response == null ? planId : response.planId();
        return new PlanGraphState(threadId, userId, resolvedPlanId, userTurn, operation, planRequest,
                segmentId, source, clientActionId, patchPayload, initialRoute, agentContext, contextPack,
                interactionDecision, directPatch, delta, response, nextNode, failure, events);
    }

    public PlanGraphState withFailure(Throwable failure) {
        return new PlanGraphState(threadId, userId, planId, userTurn, operation, planRequest, segmentId,
                source, clientActionId, patchPayload, initialRoute, agentContext, contextPack,
                interactionDecision, directPatch, delta, response, nextNode, failure, events);
    }
}
