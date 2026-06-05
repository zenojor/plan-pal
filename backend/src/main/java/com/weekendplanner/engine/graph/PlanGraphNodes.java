package com.weekendplanner.engine.graph;

import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.interaction.InteractionCommand;
import com.weekendplanner.engine.interaction.InteractionDecision;
import com.weekendplanner.engine.routing.InitialRouteMode;
import com.weekendplanner.engine.workflow.WorkflowActionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class PlanGraphNodes {

    public static final String UNDERSTAND_INITIAL = "understand_initial";
    public static final String INITIAL_ROUTE = "initial_route";
    public static final String QA_INITIAL = "qa_initial";
    public static final String CONSULT = "consult";
    public static final String RESEARCH_CANDIDATES = "research_candidates";
    public static final String CLARIFY = "clarify";
    public static final String PLAN_CHOICE = "plan_choice";
    public static final String CREATE_PLAN = "create_plan";

    public static final String ASSEMBLE_CONTEXT = "assemble_context";
    public static final String INTERACTION_ROUTE = "interaction_route";
    public static final String QA_ANSWER = "qa_answer";
    public static final String CANCEL_PENDING = "cancel_pending";
    public static final String START_NEW_PLAN = "start_new_plan";
    public static final String CONTINUE_PENDING = "continue_pending";
    public static final String AGENT_ROUTE = "agent_route";
    public static final String PATCH_EXTRACT = "patch_extract";
    public static final String PATCH_APPLY = "patch_apply";
    public static final String CANDIDATE_SEARCH = "candidate_search";
    public static final String EMIT_FINISH = "emit_finish";

    private final WorkflowActionService actions;

    public PlanGraphNodes(WorkflowActionService actions) {
        this.actions = actions;
    }

    public PlanGraphState understandInitial(PlanGraphState state) {
        return state.withInitialRoute(actions.routeInitial(state.planRequest())).withNext(INITIAL_ROUTE);
    }

    public PlanGraphState initialRoute(PlanGraphState state) {
        return state;
    }

    public PlanGraphState qaInitial(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        PlanResponse response = actions.answerInitialQuestion(state.planRequest(),
                event -> emitSse(eventConsumer, QA_INITIAL, event));
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState consult(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        PlanResponse response = actions.consultInitial(state.planRequest(), state.initialRoute(),
                event -> emitSse(eventConsumer, CONSULT, event));
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState researchCandidates(PlanGraphState state,
                                             Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        PlanResponse response = actions.researchInitial(state.planRequest(), state.initialRoute(),
                event -> emitSse(eventConsumer, RESEARCH_CANDIDATES, event));
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState clarify(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        PlanResponse response = actions.createClarificationDraft(state.planRequest(), state.initialRoute(),
                event -> emitSse(eventConsumer, CLARIFY, event));
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState planChoice(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        PlanResponse response = actions.createPlanChoiceDraft(state.planRequest(), state.initialRoute(),
                event -> emitSse(eventConsumer, PLAN_CHOICE, event));
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState createPlan(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        boolean streaming = "create_plan_stream".equalsIgnoreCase(state.operation());
        PlanResponse response = actions.createDirectPlan(state.planRequest(),
                event -> emitSse(eventConsumer, CREATE_PLAN, event), streaming);
        return state.withResponse(response).withNext(EMIT_FINISH);
    }

    public PlanGraphState assembleContext(PlanGraphState state) {
        AgentContext context = actions.assembleChatContext(state.planId(), state.userId(), state.userTurn(),
                state.segmentId(), state.source(), state.clientActionId());
        return state.withAgentContext(context).withNext(INTERACTION_ROUTE);
    }

    public PlanGraphState interactionRoute(PlanGraphState state,
                                           Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String interactionSource = actions.mergeInteractionSource(state.source(), state.clientActionId());
        InteractionDecision decision = actions.routeInteraction(state.agentContext(), interactionSource,
                state.patchPayload(), event -> emitSse(eventConsumer, INTERACTION_ROUTE, event));
        return state.withInteractionDecision(decision)
                .withDirectPatch(actions.parseDirectPatch(state.patchPayload(), interactionSource, state.segmentId()))
                .withNext(INTERACTION_ROUTE);
    }

    public PlanGraphState qaAnswer(PlanGraphState state, Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        actions.answerContextualQuestion(state.agentContext(), event -> emitSse(eventConsumer, QA_ANSWER, event));
        return state.withNext(EMIT_FINISH);
    }

    public PlanGraphState cancelPending(PlanGraphState state,
                                        Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        actions.cancelPendingAction(state.agentContext(), event -> emitSse(eventConsumer, CANCEL_PENDING, event));
        return state.withNext(EMIT_FINISH);
    }

    public PlanGraphState startNewPlan(PlanGraphState state,
                                       Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        actions.startNewPlanFromChat(state.userId(), state.userTurn(),
                event -> emitSse(eventConsumer, START_NEW_PLAN, event));
        return state.withNext(EMIT_FINISH);
    }

    public PlanGraphState continuePending(PlanGraphState state,
                                          Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        String interactionSource = actions.mergeInteractionSource(state.source(), state.clientActionId());
        boolean handled = actions.continuePendingWorkflow(state.agentContext(), state.interactionDecision(),
                interactionSource, state.userTurn(), state.directPatch(),
                event -> emitSse(eventConsumer, CONTINUE_PENDING, event));
        return state.withNext(handled ? EMIT_FINISH : AGENT_ROUTE);
    }

    public PlanGraphState agentRoute(PlanGraphState state,
                                     Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer) {
        actions.runAgentCommandPath(state.agentContext(), state.interactionDecision(), state.source(),
                state.directPatch(), event -> emitSse(eventConsumer, AGENT_ROUTE, event));
        return state.withNext(EMIT_FINISH);
    }

    public String routeAfterInitial(PlanGraphState state) {
        if (state.initialRoute() == null || state.initialRoute().mode() == null) return CREATE_PLAN;
        if (state.initialRoute().mode() == InitialRouteMode.CONVERSATIONAL_QA) return QA_INITIAL;
        if (state.initialRoute().mode() == InitialRouteMode.CONSULT_CHAT) return CONSULT;
        if (state.initialRoute().mode() == InitialRouteMode.RESEARCH_AND_RENDER) return RESEARCH_CANDIDATES;
        if (state.initialRoute().mode() == InitialRouteMode.ASK_CLARIFICATION) return CLARIFY;
        if (actions.shouldOfferInitialPlanChoices(state.planRequest())) return PLAN_CHOICE;
        return CREATE_PLAN;
    }

    public String routeAfterInteraction(PlanGraphState state) {
        InteractionDecision decision = state.interactionDecision();
        if (decision == null || decision.command() == null) return AGENT_ROUTE;
        InteractionCommand command = decision.command();
        if (command == InteractionCommand.CONVERSATIONAL_QA || command == InteractionCommand.SMALLTALK_HELP) {
            return QA_ANSWER;
        }
        if (command == InteractionCommand.CANCEL_PENDING) return CANCEL_PENDING;
        if (command == InteractionCommand.START_NEW_PLAN) return START_NEW_PLAN;
        if (command == InteractionCommand.CONTINUE_WORKFLOW) return CONTINUE_PENDING;
        return AGENT_ROUTE;
    }

    public List<String> nodeNames() {
        return List.of(UNDERSTAND_INITIAL, INITIAL_ROUTE, QA_INITIAL, CONSULT, RESEARCH_CANDIDATES,
                CLARIFY, PLAN_CHOICE, CREATE_PLAN, ASSEMBLE_CONTEXT, INTERACTION_ROUTE, QA_ANSWER,
                CANCEL_PENDING, START_NEW_PLAN, CONTINUE_PENDING, AGENT_ROUTE, PATCH_EXTRACT,
                PATCH_APPLY, CANDIDATE_SEARCH, EMIT_FINISH);
    }

    private void emitSse(Consumer<PlanGraphEvents.PlanGraphEvent> eventConsumer, String node, com.weekendplanner.dto.SseEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(PlanGraphEvents.sse(node, event));
        }
    }
}
