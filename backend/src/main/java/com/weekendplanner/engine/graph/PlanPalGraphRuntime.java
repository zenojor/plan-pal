package com.weekendplanner.engine.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import org.springframework.stereotype.Component;

import java.util.List;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class PlanPalGraphRuntime {

    private final PlanGraphConfig config;
    private final PlanGraphNodes nodes;
    private final ObjectMapper objectMapper;

    public PlanPalGraphRuntime(PlanGraphConfig config, PlanGraphNodes nodes, ObjectMapper objectMapper) {
        this.config = config;
        this.nodes = nodes;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper.findAndRegisterModules();
    }

    public PlanResponse createPlan(PlanRequest request,
                                   Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        return invokeCreateGraph(request, "create_plan", eventConsumer);
    }

    public PlanResponse createPlanStreaming(PlanRequest request,
                                            Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        return invokeCreateGraph(request, "create_plan_stream", eventConsumer);
    }

    public void executeChat(String planId,
                            String userId,
                            String prompt,
                            String segmentId,
                            String source,
                            String clientActionId,
                            String patchPayload,
                            Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String threadId = userId + ":" + planId;
        PlanGraphState state = PlanGraphState.chat(threadId, planId, userId, prompt,
                segmentId, source, clientActionId, patchPayload);
        try {
            CompiledGraph graph = chatGraph(eventConsumer);
            graph.invoke(Map.of("state", state), RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            throw new IllegalStateException("Plan chat graph execution failed", e);
        }
    }

    public boolean enabled() {
        return config.enabled();
    }

    public boolean chatEnabled() {
        return config.chatEnabled();
    }

    private PlanResponse invokeCreateGraph(PlanRequest request,
                                           String operation,
                                           Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String threadId = request.userId() + ":" + (request.planId() == null ? UUID.randomUUID() : request.planId());
        PlanGraphState state = PlanGraphState.create(threadId, request, operation);
        try {
            CompiledGraph graph = createGraph(eventConsumer);
            Optional<OverAllState> result = graph.invoke(Map.of("state", state),
                    RunnableConfig.builder().threadId(threadId).build());
            PlanResponse response = result.flatMap(overAllState -> overAllState.value("state", PlanGraphState.class))
                    .map(PlanGraphState::response)
                    .orElseThrow(() -> new IllegalStateException("Plan graph did not produce a response"));
            return cleanResponse(response);
        } catch (Exception e) {
            throw new IllegalStateException("Plan graph execution failed", e);
        }
    }

    private PlanResponse cleanResponse(PlanResponse response) {
        if (response == null) {
            return null;
        }
        List<PlanStep> timeline = response.timeline() == null ? List.of() : ((List<?>) response.timeline()).stream()
                .map(item -> convertIfNeeded(item, PlanStep.class))
                .toList();
        List<WorkflowTrace> trace = response.trace() == null ? List.of() : ((List<?>) response.trace()).stream()
                .map(item -> convertIfNeeded(item, WorkflowTrace.class))
                .toList();
        List<OrderIntent> orderIntents = response.orderIntents() == null ? List.of() : ((List<?>) response.orderIntents()).stream()
                .map(item -> convertIfNeeded(item, OrderIntent.class))
                .toList();
        List<Conflict> conflicts = response.conflicts() == null ? List.of() : ((List<?>) response.conflicts()).stream()
                .map(item -> convertIfNeeded(item, Conflict.class))
                .toList();
        List<RepairOption> repairOptions = response.repairOptions() == null ? List.of() : ((List<?>) response.repairOptions()).stream()
                .map(item -> convertIfNeeded(item, RepairOption.class))
                .toList();
        List<PlanResponse> variants = response.variants() == null ? List.of() : ((List<?>) response.variants()).stream()
                .map(item -> convertIfNeeded(item, PlanResponse.class))
                .map(this::cleanResponse)
                .toList();

        PlanIntent intent = response.intent();
        WeatherSnapshot weather = response.weather();

        return new PlanResponse(
                response.planId(),
                response.userId(),
                response.status(),
                response.summary(),
                timeline,
                trace,
                response.orderGroupId(),
                response.notificationText(),
                response.degradationNote(),
                intent,
                orderIntents,
                response.executionStatus(),
                response.version(),
                response.planStatus(),
                conflicts,
                repairOptions,
                weather,
                variants
        );
    }

    private <T> T convertIfNeeded(Object item, Class<T> type) {
        if (type.isInstance(item)) {
            return type.cast(item);
        }
        return objectMapper.convertValue(item, type);
    }

    private CompiledGraph createGraph(Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) throws Exception {
        StateGraph graph = new StateGraph("plan-pal-create", Map::of);
        addNode(graph, PlanGraphNodes.UNDERSTAND_INITIAL, state -> nodes.understandInitial(state), eventConsumer);
        addNode(graph, PlanGraphNodes.INITIAL_ROUTE, nodes::initialRoute, eventConsumer);
        addNode(graph, PlanGraphNodes.QA_INITIAL, state -> nodes.qaInitial(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.CONSULT, state -> nodes.consult(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.RESEARCH_CANDIDATES, state -> nodes.researchCandidates(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.CLARIFY, state -> nodes.clarify(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.PLAN_CHOICE, state -> nodes.planChoice(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.CREATE_PLAN, state -> nodes.createPlan(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.EMIT_FINISH, state -> state, eventConsumer);

        graph.addEdge(StateGraph.START, PlanGraphNodes.UNDERSTAND_INITIAL);
        graph.addEdge(PlanGraphNodes.UNDERSTAND_INITIAL, PlanGraphNodes.INITIAL_ROUTE);
        graph.addConditionalEdges(PlanGraphNodes.INITIAL_ROUTE,
                AsyncEdgeAction.edge_async(overAllState -> nodes.routeAfterInitial(readState(overAllState))),
                Map.of(
                        PlanGraphNodes.QA_INITIAL, PlanGraphNodes.QA_INITIAL,
                        PlanGraphNodes.CONSULT, PlanGraphNodes.CONSULT,
                        PlanGraphNodes.RESEARCH_CANDIDATES, PlanGraphNodes.RESEARCH_CANDIDATES,
                        PlanGraphNodes.CLARIFY, PlanGraphNodes.CLARIFY,
                        PlanGraphNodes.PLAN_CHOICE, PlanGraphNodes.PLAN_CHOICE,
                        PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.CREATE_PLAN
                ));
        graph.addEdge(PlanGraphNodes.QA_INITIAL, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.CONSULT, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.RESEARCH_CANDIDATES, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.CLARIFY, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.PLAN_CHOICE, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.EMIT_FINISH, StateGraph.END);
        return graph.compile();
    }

    private CompiledGraph chatGraph(Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) throws Exception {
        StateGraph graph = new StateGraph("plan-pal-chat", Map::of);
        addNode(graph, PlanGraphNodes.ASSEMBLE_CONTEXT, nodes::assembleContext, eventConsumer);
        addNode(graph, PlanGraphNodes.INTERACTION_ROUTE, state -> nodes.interactionRoute(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.QA_ANSWER, state -> nodes.qaAnswer(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.CANCEL_PENDING, state -> nodes.cancelPending(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.START_NEW_PLAN, state -> nodes.startNewPlan(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.CONTINUE_PENDING, state -> nodes.continuePending(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.AGENT_ROUTE, state -> nodes.agentRoute(state, eventConsumer), eventConsumer);
        addNode(graph, PlanGraphNodes.EMIT_FINISH, state -> state, eventConsumer);

        graph.addEdge(StateGraph.START, PlanGraphNodes.ASSEMBLE_CONTEXT);
        graph.addEdge(PlanGraphNodes.ASSEMBLE_CONTEXT, PlanGraphNodes.INTERACTION_ROUTE);
        graph.addConditionalEdges(PlanGraphNodes.INTERACTION_ROUTE,
                AsyncEdgeAction.edge_async(overAllState -> nodes.routeAfterInteraction(readState(overAllState))),
                Map.of(
                        PlanGraphNodes.QA_ANSWER, PlanGraphNodes.QA_ANSWER,
                        PlanGraphNodes.CANCEL_PENDING, PlanGraphNodes.CANCEL_PENDING,
                        PlanGraphNodes.START_NEW_PLAN, PlanGraphNodes.START_NEW_PLAN,
                        PlanGraphNodes.CONTINUE_PENDING, PlanGraphNodes.CONTINUE_PENDING,
                        PlanGraphNodes.AGENT_ROUTE, PlanGraphNodes.AGENT_ROUTE
                ));
        graph.addConditionalEdges(PlanGraphNodes.CONTINUE_PENDING,
                AsyncEdgeAction.edge_async(overAllState -> readState(overAllState).nextNode()),
                Map.of(
                        PlanGraphNodes.EMIT_FINISH, PlanGraphNodes.EMIT_FINISH,
                        PlanGraphNodes.AGENT_ROUTE, PlanGraphNodes.AGENT_ROUTE
                ));
        graph.addEdge(PlanGraphNodes.QA_ANSWER, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.CANCEL_PENDING, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.START_NEW_PLAN, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.AGENT_ROUTE, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.EMIT_FINISH, StateGraph.END);
        return graph.compile();
    }

    private void addNode(StateGraph graph,
                         String name,
                         GraphNodeAction action,
                         Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) throws Exception {
        graph.addNode(name, AsyncNodeAction.node_async(overAllState -> {
            PlanGraphState state = readState(overAllState);
            emit(eventConsumer, PlanGraphEvents.internal(name, "ACTION", "graph.node: " + name));
            return Map.of("state", action.apply(state));
        }));
    }

    private PlanGraphState readState(OverAllState state) {
        return state.value("state", PlanGraphState.class)
                .orElseThrow(() -> new IllegalStateException("Graph state is required"));
    }

    private void emit(Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer,
                      PlanGraphEvents.PlanGraphEvent event) {
        if (eventConsumer != null) eventConsumer.accept(event);
    }

    @FunctionalInterface
    private interface GraphNodeAction {
        PlanGraphState apply(PlanGraphState state);
    }
}
