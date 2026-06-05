package com.weekendplanner.engine.routing;

import com.weekendplanner.engine.understanding.DomainIntent;
import com.weekendplanner.engine.understanding.RouteTarget;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.SlotValue;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
public class InitialTurnRouter {

    private static final Logger log = LoggerFactory.getLogger(InitialTurnRouter.class);

    private final TurnUnderstandingService understandingService;

    public InitialTurnRouter(TurnUnderstandingService understandingService) {
        this.understandingService = understandingService == null
                ? TurnUnderstandingService.fallbackOnly()
                : understandingService;
    }

    public InitialRouteCommand route(String prompt) {
        return route(prompt, "initial", null);
    }

    public InitialRouteCommand route(String prompt, String source, String structuredMarker) {
        if (isStructuredBuildMarker(structuredMarker) || containsStructuredBuildMarker(prompt)) {
            TurnUnderstanding markerUnderstanding = TurnUnderstanding.builder()
                    .turnIntent(TurnIntent.PLAN_BUILD)
                    .domainIntent(DomainIntent.GENERIC_TRIP)
                    .routeTarget(RouteTarget.PLAN)
                    .confidence(1.0)
                    .reasonCode("structured.build_marker")
                    .build();
            return new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 1.0, null,
                    evidence(markerUnderstanding), null, markerUnderstanding);
        }

        TurnUnderstanding understanding = understandingService.understandInitial(prompt == null ? "" : prompt);
        InitialRouteCommand command = routeFromUnderstanding(understanding);
        log.debug("[InitialRoute] source={} turnIntent={} domain={} target={} mode={} reason={}",
                source, understanding.turnIntent(), understanding.domainIntent(), understanding.routeTarget(),
                command.mode(), understanding.reasonCode());
        return command;
    }

    public IntentEvidence evidence(String prompt) {
        return evidence(understandingService.understandInitial(prompt == null ? "" : prompt));
    }

    private InitialRouteCommand routeFromUnderstanding(TurnUnderstanding understanding) {
        TurnUnderstanding value = understanding == null ? TurnUnderstanding.empty() : understanding;
        IntentEvidence evidence = evidence(value);
        String researchType = researchType(value.domainIntent());

        if (value.routeTarget() == RouteTarget.QA
                || value.turnIntent() == TurnIntent.GENERAL_QA
                || value.turnIntent() == TurnIntent.SMALLTALK
                || value.turnIntent() == TurnIntent.READ_ONLY_QUESTION) {
            return command(InitialRouteMode.CONVERSATIONAL_QA, value, "QA", evidence, null);
        }
        if (value.routeTarget() == RouteTarget.RESEARCH || value.turnIntent() == TurnIntent.TRIP_RESEARCH) {
            return command(InitialRouteMode.RESEARCH_AND_RENDER, value, researchType, evidence, null);
        }
        if (value.routeTarget() == RouteTarget.CONSULT || value.turnIntent() == TurnIntent.TRIP_IDEA) {
            return command(InitialRouteMode.CONSULT_CHAT, value, "IDEA", evidence, null);
        }
        if (value.routeTarget() == RouteTarget.CLARIFY || value.turnIntent() == TurnIntent.ASK_CLARIFICATION) {
            return command(InitialRouteMode.ASK_CLARIFICATION, value, null, evidence,
                    clarificationQuestion(evidence));
        }
        if (value.routeTarget() == RouteTarget.PLAN
                || value.turnIntent() == TurnIntent.PLAN_BUILD
                || value.turnIntent() == TurnIntent.START_NEW_PLAN) {
            if (!evidence.timeSignal() && !evidence.headcountSignal()) {
                return command(InitialRouteMode.ASK_CLARIFICATION, value, null, evidence,
                        clarificationQuestion(evidence));
            }
            return command(InitialRouteMode.CREATE_PLAN, value, null, evidence, null);
        }
        if (value.hasSlots() && evidence.timeSignal() && evidence.headcountSignal()) {
            return command(InitialRouteMode.CREATE_PLAN, value, null, evidence, null);
        }
        return command(InitialRouteMode.CONVERSATIONAL_QA, value, "QA", evidence, null);
    }

    private InitialRouteCommand command(InitialRouteMode mode,
                                        TurnUnderstanding understanding,
                                        String researchType,
                                        IntentEvidence evidence,
                                        String clarificationQuestion) {
        double confidence = understanding == null ? 0.0 : understanding.confidence();
        return new InitialRouteCommand(mode, confidence, researchType, evidence,
                clarificationQuestion, understanding);
    }

    private IntentEvidence evidence(TurnUnderstanding understanding) {
        TurnUnderstanding value = understanding == null ? TurnUnderstanding.empty() : understanding;
        boolean hasTime = value.slot(SlotName.START_TIME).isPresent()
                || value.slot(SlotName.END_TIME).isPresent()
                || value.slot(SlotName.MAX_END_TIME).isPresent()
                || value.slot(SlotName.TIME_RANGE).isPresent();
        boolean hasHeadcount = value.slot(SlotName.HEADCOUNT).isPresent();
        boolean hasPlan = value.turnIntent() == TurnIntent.PLAN_BUILD
                || value.turnIntent() == TurnIntent.ASK_CLARIFICATION
                || value.routeTarget() == RouteTarget.PLAN
                || value.routeTarget() == RouteTarget.CLARIFY;
        boolean hasExplore = value.turnIntent() == TurnIntent.TRIP_IDEA
                || value.turnIntent() == TurnIntent.TRIP_RESEARCH
                || value.routeTarget() == RouteTarget.CONSULT
                || value.routeTarget() == RouteTarget.RESEARCH;
        boolean hasMovie = value.domainIntent() == DomainIntent.MOVIE;
        boolean hasFood = value.domainIntent() == DomainIntent.DINING
                || value.domainIntent() == DomainIntent.DINING_LOCKED_PLAN;
        return new IntentEvidence(hasTime, hasHeadcount, hasPlan, hasExplore, hasMovie,
                hasFood, false, afterTime(value).orElse(null));
    }

    private Optional<String> afterTime(TurnUnderstanding understanding) {
        Optional<String> start = understanding.slot(SlotName.START_TIME)
                .map(SlotValue::value)
                .map(Objects::toString)
                .filter(value -> !value.isBlank());
        if (start.isPresent()) return start;
        return understanding.slot(SlotName.TIME_RANGE)
                .map(SlotValue::value)
                .map(Objects::toString)
                .map(value -> switch (value.toUpperCase(Locale.ROOT)) {
                    case "MORNING" -> "10:00";
                    case "NOON" -> "12:00";
                    case "AFTERNOON" -> "14:00";
                    case "EVENING", "NIGHT" -> "19:00";
                    default -> null;
                });
    }

    private String researchType(DomainIntent domainIntent) {
        if (domainIntent == DomainIntent.MOVIE) return "MOVIE";
        if (domainIntent == DomainIntent.PRODUCT) return "PRODUCT";
        if (domainIntent == DomainIntent.DINING || domainIntent == DomainIntent.DINING_LOCKED_PLAN) return "DINING";
        if (domainIntent == DomainIntent.ACTIVITY) return "ACTIVITY";
        return "IDEA";
    }

    private String clarificationQuestion(IntentEvidence evidence) {
        if (evidence == null || (!evidence.timeSignal() && !evidence.headcountSignal())) {
            return "先选一下出行时间，再告诉我几个人去。";
        }
        if (!evidence.timeSignal()) return "先选一下出行时间。";
        if (!evidence.headcountSignal()) return "几个人一起去？";
        return "还差一点信息，补一下我再继续安排。";
    }

    private boolean isStructuredBuildMarker(String structuredMarker) {
        if (structuredMarker == null || structuredMarker.isBlank()) return false;
        return structuredMarker.trim().equalsIgnoreCase("BUILD_SELECTED_PLAN");
    }

    private boolean containsStructuredBuildMarker(String prompt) {
        return prompt != null && prompt.toUpperCase(Locale.ROOT).contains("BUILD_SELECTED_PLAN");
    }
}
