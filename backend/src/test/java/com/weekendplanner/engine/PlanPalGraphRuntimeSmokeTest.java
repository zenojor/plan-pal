package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.graph.PlanGraphConfig;
import com.weekendplanner.engine.graph.PlanGraphEvents;
import com.weekendplanner.engine.graph.PlanGraphNodes;
import com.weekendplanner.engine.graph.PlanPalGraphRuntime;
import com.weekendplanner.engine.workflow.AgentWorkflowEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanPalGraphRuntimeSmokeTest {

    @Test
    void createPlanRunsThroughSpringAiAlibabaGraphCore() {
        AgentWorkflowEngine workflowEngine = mock(AgentWorkflowEngine.class);
        PlanResponse expected = new PlanResponse("plan-1", "U001", "SUCCESS", "summary",
                List.of(), List.of(), "orders-1", "notify", "");
        when(workflowEngine.createPlan(any(PlanRequest.class))).thenReturn(expected);
        PlanPalGraphRuntime runtime = new PlanPalGraphRuntime(new PlanGraphConfig(), new PlanGraphNodes());
        List<PlanGraphEvents.PlanGraphEvent> events = new ArrayList<>();

        PlanResponse response = runtime.createPlan(new PlanRequest("U001", "make a plan"),
                workflowEngine, events::add);

        assertThat(response).isEqualTo(expected);
        assertThat(events).extracting(PlanGraphEvents.PlanGraphEvent::node)
                .containsExactly(PlanGraphNodes.INITIAL_ROUTE, PlanGraphNodes.CREATE_PLAN, PlanGraphNodes.EMIT_FINISH);
        assertThat(events).extracting(PlanGraphEvents.PlanGraphEvent::type)
                .containsExactly("START", "ACTION", "FINISH");
        verify(workflowEngine).createPlan(any(PlanRequest.class));
    }
}
