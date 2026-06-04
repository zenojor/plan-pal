package com.weekendplanner.engine.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.workflow.AgentWorkflowEngine;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class PlanPalGraphRuntime {

    private final PlanGraphConfig config;
    private final PlanGraphNodes nodes;

    public PlanPalGraphRuntime(PlanGraphConfig config, PlanGraphNodes nodes) {
        this.config = config;
        this.nodes = nodes;
    }

    public PlanResponse createPlan(PlanRequest request,
                                   AgentWorkflowEngine workflowEngine,
                                   Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String threadId = request.userId() + ":" + (request.planId() == null ? UUID.randomUUID() : request.planId());
        PlanGraphState state = new PlanGraphState(threadId, request.userId(), request.planId(), request.prompt(),
                "create_plan", request, null, null, PlanGraphNodes.INITIAL_ROUTE, java.util.List.of());
        try {
            CompiledGraph graph = createPlanGraph(nodes, workflowEngine, eventConsumer);
            Optional<OverAllState> result = graph.invoke(Map.of("state", state),
                    RunnableConfig.builder().threadId(threadId).build());
            return result.flatMap(overAllState -> overAllState.value("state", PlanGraphState.class))
                    .map(PlanGraphState::response)
                    .orElseThrow(() -> new IllegalStateException("Plan graph did not produce a response"));
        } catch (Exception e) {
            throw new IllegalStateException("Plan graph execution failed", e);
        }
    }

    public PlanResponse createPlanStreaming(PlanRequest request,
                                            AgentWorkflowEngine workflowEngine,
                                            Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String threadId = request.userId() + ":" + (request.planId() == null ? UUID.randomUUID() : request.planId());
        PlanGraphState state = new PlanGraphState(threadId, request.userId(), request.planId(), request.prompt(),
                "create_plan_stream", request, null, null, PlanGraphNodes.INITIAL_ROUTE, java.util.List.of());
        try {
            CompiledGraph graph = createPlanStreamingGraph(workflowEngine, eventConsumer);
            Optional<OverAllState> result = graph.invoke(Map.of("state", state),
                    RunnableConfig.builder().threadId(threadId).build());
            return result.flatMap(overAllState -> overAllState.value("state", PlanGraphState.class))
                    .map(PlanGraphState::response)
                    .orElseThrow(() -> new IllegalStateException("Plan graph did not produce a response"));
        } catch (Exception e) {
            throw new IllegalStateException("Plan graph streaming execution failed", e);
        }
    }

    public boolean enabled() {
        return config.enabled();
    }

    private void emit(Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer,
                      PlanGraphEvents.PlanGraphEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    private CompiledGraph createPlanGraph(PlanGraphNodes nodes,
                                          AgentWorkflowEngine workflowEngine,
                                          Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) throws Exception {
        StateGraph graph = new StateGraph("plan-pal-create-plan", Map::of);
        graph.addNode(PlanGraphNodes.INITIAL_ROUTE, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            emit(eventConsumer, PlanGraphEvents.internal(PlanGraphNodes.INITIAL_ROUTE, "START",
                    "graph.initial_route"));
            return Map.of("state", nodes.initialRoute(graphState));
        }));
        graph.addNode(PlanGraphNodes.CREATE_PLAN, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            emit(eventConsumer, PlanGraphEvents.internal(PlanGraphNodes.CREATE_PLAN, "ACTION",
                    "graph.create_plan"));
            return Map.of("state", nodes.createPlan(graphState, workflowEngine));
        }));
        graph.addNode(PlanGraphNodes.EMIT_FINISH, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            emit(eventConsumer, PlanGraphEvents.internal(PlanGraphNodes.EMIT_FINISH, "FINISH",
                    "graph.emit_finish"));
            return Map.of("state", graphState);
        }));
        graph.addEdge(StateGraph.START, PlanGraphNodes.INITIAL_ROUTE);
        graph.addEdge(PlanGraphNodes.INITIAL_ROUTE, PlanGraphNodes.CREATE_PLAN);
        graph.addEdge(PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.EMIT_FINISH, StateGraph.END);
        return graph.compile();
    }

    private CompiledGraph createPlanStreamingGraph(AgentWorkflowEngine workflowEngine,
                                                   Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) throws Exception {
        StateGraph graph = new StateGraph("plan-pal-create-plan-stream", Map::of);
        graph.addNode(PlanGraphNodes.INITIAL_ROUTE, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            emit(eventConsumer, PlanGraphEvents.internal(PlanGraphNodes.INITIAL_ROUTE, "START",
                    "graph.initial_route"));
            return Map.of("state", nodes.initialRoute(graphState));
        }));
        graph.addNode(PlanGraphNodes.CREATE_PLAN, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            PlanResponse response = workflowEngine.createPlanStreaming(graphState.planRequest(),
                    event -> emit(eventConsumer, PlanGraphEvents.sse(PlanGraphNodes.CREATE_PLAN, event)));
            return Map.of("state", graphState.withResponse(response).withNext(PlanGraphNodes.EMIT_FINISH));
        }));
        graph.addNode(PlanGraphNodes.EMIT_FINISH, AsyncNodeAction.node_async(state -> {
            PlanGraphState graphState = state.value("state", PlanGraphState.class)
                    .orElseThrow(() -> new IllegalStateException("Graph state is required"));
            emit(eventConsumer, PlanGraphEvents.internal(PlanGraphNodes.EMIT_FINISH, "FINISH",
                    "graph.emit_finish"));
            return Map.of("state", graphState);
        }));
        graph.addEdge(StateGraph.START, PlanGraphNodes.INITIAL_ROUTE);
        graph.addEdge(PlanGraphNodes.INITIAL_ROUTE, PlanGraphNodes.CREATE_PLAN);
        graph.addEdge(PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.EMIT_FINISH);
        graph.addEdge(PlanGraphNodes.EMIT_FINISH, StateGraph.END);
        return graph.compile();
    }
}
