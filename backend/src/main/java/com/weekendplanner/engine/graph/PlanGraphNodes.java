package com.weekendplanner.engine.graph;

import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.workflow.AgentWorkflowEngine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlanGraphNodes {

    public static final String INITIAL_ROUTE = "initial_route";
    public static final String ASSEMBLE_CONTEXT = "assemble_context";
    public static final String CONSULT_OR_RESEARCH = "consult_or_research";
    public static final String CREATE_PLAN = "create_plan";
    public static final String INTERACTION_ROUTE = "interaction_route";
    public static final String QA_ANSWER = "qa_answer";
    public static final String CANDIDATE_SELECT = "candidate_select";
    public static final String PATCH_EXTRACT = "patch_extract";
    public static final String PATCH_APPLY = "patch_apply";
    public static final String CANDIDATE_SEARCH = "candidate_search";
    public static final String EMIT_FINISH = "emit_finish";

    public PlanGraphState initialRoute(PlanGraphState state) {
        return state.withNext(CREATE_PLAN);
    }

    public PlanGraphState createPlan(PlanGraphState state, AgentWorkflowEngine workflowEngine) {
        PlanResponse response = workflowEngine.createPlan(state.planRequest());
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public List<String> nodeNames() {
        return List.of(INITIAL_ROUTE, ASSEMBLE_CONTEXT, CONSULT_OR_RESEARCH, CREATE_PLAN,
                INTERACTION_ROUTE, QA_ANSWER, CANDIDATE_SELECT, PATCH_EXTRACT, PATCH_APPLY,
                CANDIDATE_SEARCH, EMIT_FINISH);
    }
}
