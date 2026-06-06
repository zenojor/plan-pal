package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.graph.PlanGraphConfig;
import com.weekendplanner.engine.graph.PlanGraphEvents;
import com.weekendplanner.engine.graph.PlanGraphNodes;
import com.weekendplanner.engine.graph.PlanPalGraphRuntime;
import com.weekendplanner.engine.interaction.InteractionCommand;
import com.weekendplanner.engine.interaction.InteractionDecision;
import com.weekendplanner.engine.routing.InitialRouteCommand;
import com.weekendplanner.engine.routing.InitialRouteMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.workflow.WorkflowActionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanPalGraphRuntimeSmokeTest {

    @Test
    void createPlanRunsThroughSpringAiAlibabaGraphCore() {
        WorkflowActionService actions = mock(WorkflowActionService.class);
        PlanResponse expected = new PlanResponse("plan-1", "U001", "SUCCESS", "summary",
                List.of(), List.of(), "orders-1", "notify", "");
        when(actions.routeInitial(any(PlanRequest.class))).thenReturn(
                new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 0.9, null, null, null));
        when(actions.shouldOfferInitialPlanChoices(any(PlanRequest.class))).thenReturn(false);
        when(actions.createDirectPlan(any(PlanRequest.class), any(), eq(false))).thenReturn(expected);
        PlanPalGraphRuntime runtime = new PlanPalGraphRuntime(new PlanGraphConfig(), new PlanGraphNodes(actions), new ObjectMapper());
        List<PlanGraphEvents.PlanGraphEvent> events = new ArrayList<>();

        PlanResponse response = runtime.createPlan(new PlanRequest("U001", "make a plan"), events::add);

        assertThat(response).isEqualTo(expected);
        assertThat(events).extracting(PlanGraphEvents.PlanGraphEvent::node)
                .containsExactly(PlanGraphNodes.UNDERSTAND_INITIAL, PlanGraphNodes.INITIAL_ROUTE,
                        PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.EMIT_FINISH);
        verify(actions).createDirectPlan(any(PlanRequest.class), any(), eq(false));
    }

    @Test
    void chatGraphRoutesContextualQaWithoutCallingLegacyWorkflowEngine() {
        WorkflowActionService actions = mock(WorkflowActionService.class);
        ContextPack context = new ContextPack("U001", "plan-1", "why this movie?", null, null, null, List.of(), List.of(), null, List.of(), 1);
        when(actions.assembleChatContextPack("plan-1", "U001", "why this movie?", null))
                .thenReturn(context);
        when(actions.mergeInteractionSource(null, null)).thenReturn("");
        when(actions.routeInteraction(eq(context), eq(""), isNull(), any()))
                .thenReturn(InteractionDecision.of(InteractionCommand.CONVERSATIONAL_QA, 0.9, "qa"));
        PlanPalGraphRuntime runtime = new PlanPalGraphRuntime(new PlanGraphConfig(), new PlanGraphNodes(actions), new ObjectMapper());
        List<PlanGraphEvents.PlanGraphEvent> events = new ArrayList<>();

        runtime.executeChat("plan-1", "U001", "why this movie?", null, null, null, null, events::add);

        assertThat(events).extracting(PlanGraphEvents.PlanGraphEvent::node)
                .containsExactly(PlanGraphNodes.ASSEMBLE_CONTEXT, PlanGraphNodes.INTERACTION_ROUTE,
                        PlanGraphNodes.QA_ANSWER, PlanGraphNodes.EMIT_FINISH);
        verify(actions).answerContextualQuestion(eq(context), any());
    }

    @Test
    void graphRuntimeDoesNotDependOnAgentWorkflowEngine() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/weekendplanner/engine/graph/PlanPalGraphRuntime.java"));

        assertThat(source).doesNotContain("AgentWorkflowEngine");
    }
}
