package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class UnderstandingPromptFactory {

    private final ObjectMapper objectMapper;

    public UnderstandingPromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public String systemPrompt() {
        return """
                You extract structured understanding for a trip-planning agent. Output JSON only.
                Do not create a plan, patch, timeline, or tool call.
                Schema:
                {
                  "turnIntent": "GENERAL_QA|SMALLTALK|TRIP_IDEA|TRIP_RESEARCH|PLAN_BUILD|ASK_CLARIFICATION|FILL_PENDING_SLOTS|READ_ONLY_QUESTION|CANCEL_PENDING|SELECT_CANDIDATE|REFINE_CANDIDATES|MODIFY_PLAN|START_NEW_PLAN|UNKNOWN",
                  "domainIntent": "MOVIE|DINING|ACTIVITY|PRODUCT|GENERIC_TRIP|NON_TRIP|DINING_LOCKED_PLAN|CONTEXTUAL_RESEARCH|GENERIC_PLAN|UNKNOWN",
                  "routeTarget": "QA|CONSULT|RESEARCH|PLAN|CLARIFY|PATCH|WORKFLOW|UNKNOWN",
                  "slots": [
                    {"name":"HEADCOUNT","value":3,"provenance":"EXPLICIT|IMPLIED|ASSUMED|FALLBACK","confidence":0.95,"sourceText":"..."},
                    {"name":"DURATION_RANGE","minMinutes":180,"maxMinutes":240,"provenance":"EXPLICIT","confidence":0.95,"sourceText":"..."},
                    {"name":"SEARCH_TAG","value":"hotpot|tea|indoor|child_friendly","provenance":"EXPLICIT","confidence":0.95,"sourceText":"..."},
                    {"name":"SEARCH_CATEGORY","value":"RESTAURANT|PRODUCT|ACTIVITY","provenance":"EXPLICIT","confidence":0.95,"sourceText":"..."}
                  ],
                  "missingSlots": ["HEADCOUNT"],
                  "readOnlyQuestion": false,
                  "selectedCandidateIndex": null,
                  "confidence": 0.0,
                  "reasonCode": "short_snake_case"
                }
                Active pending workflow is authoritative unless the user cancels or asks a read-only question.
                If active pendingAction is SELECT_CANDIDATE and the user asks for a different kind of candidate
                (for example "any hotpot?", "nearer", "not a mall", "milk tea instead"), use REFINE_CANDIDATES
                with routeTarget WORKFLOW and SEARCH_TAG / SEARCH_CATEGORY slots.
                Product questions, identity questions, explanations, thanks, jokes, and ordinary conversation are GENERAL_QA or SMALLTALK with domainIntent NON_TRIP and routeTarget QA.
                Trip ideation such as "first date ideas" is TRIP_IDEA with routeTarget CONSULT.
                Trip searches such as "nearby restaurants" or "recommend movies" are TRIP_RESEARCH with routeTarget RESEARCH.
                Product searches such as "which milk tea is good nearby" are TRIP_RESEARCH with domainIntent PRODUCT and routeTarget RESEARCH.
                Concrete itinerary requests are PLAN_BUILD when enough slots exist, otherwise ASK_CLARIFICATION.
                "What is this movie about?" is READ_ONLY_QUESTION. "Afternoon, nearby" with active pending is FILL_PENDING_SLOTS.
                "Three friends" means headcount 3. "Me and three friends" means headcount 4.
                "Start at 10am, three to four hours, activity before dining" means START_TIME=10:00,
                DURATION_RANGE=180..240, MAX_END_TIME=14:00, ORDER_PREFERENCE=ACTIVITY_THEN_DINING.
                Use EXPLICIT only when the user turn directly says or clearly refers to the value.
                """;
    }

    public String userPayload(UnderstandingRequest request) {
        try {
            PendingAction pending = request == null ? null : request.pendingAction();
            List<CandidateSet> candidates = request == null ? List.of() : request.activeCandidates();
            List<RecentEvent> events = request == null ? List.of() : request.recentEvents();
            List<PlanStep> timeline = request == null ? List.of() : request.timeline();
            return objectMapper.writeValueAsString(Map.of(
                    "userTurn", request == null ? "" : request.userTurn(),
                    "source", request == null ? "" : request.source(),
                    "pendingAction", pending == null ? Map.of() : pending,
                    "lastCandidates", candidates,
                    "recentEvents", events,
                    "timeline", summarizeTimeline(timeline)
            ));
        } catch (Exception e) {
            return "{\"userTurn\":\"\"}";
        }
    }

    private List<Map<String, Object>> summarizeTimeline(List<PlanStep> timeline) {
        if (timeline == null) return List.of();
        return timeline.stream()
                .filter(step -> step != null && !step.isTransit())
                .map(step -> Map.<String, Object>of(
                        "segmentId", step.segmentId(),
                        "phase", step.phase(),
                        "startTime", step.startTime(),
                        "endTime", step.endTime(),
                        "poiId", step.poiId(),
                        "poiName", step.poiName()))
                .toList();
    }
}
