package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.dto.PlanStep;
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
                  "turnIntent": "FILL_PENDING_SLOTS|READ_ONLY_QUESTION|CANCEL_PENDING|SELECT_CANDIDATE|MODIFY_PLAN|START_NEW_PLAN|SMALLTALK|UNKNOWN",
                  "domainIntent": "MOVIE|DINING_LOCKED_PLAN|CONTEXTUAL_RESEARCH|GENERIC_PLAN|UNKNOWN",
                  "slots": [
                    {"name":"HEADCOUNT","value":3,"provenance":"EXPLICIT|IMPLIED|ASSUMED|FALLBACK","confidence":0.95,"sourceText":"..."},
                    {"name":"DURATION_RANGE","minMinutes":180,"maxMinutes":240,"provenance":"EXPLICIT","confidence":0.95,"sourceText":"..."}
                  ],
                  "missingSlots": ["HEADCOUNT"],
                  "readOnlyQuestion": false,
                  "selectedCandidateIndex": null,
                  "confidence": 0.0,
                  "reasonCode": "short_snake_case"
                }
                Active pending workflow is authoritative unless the user cancels or asks a read-only question.
                "这个电影讲什么" is READ_ONLY_QUESTION. "下午吧就附近" with active pending is FILL_PENDING_SLOTS.
                "三个朋友" means headcount 3. "我和三个朋友" means headcount 4.
                "上午十点开始，三四个小时，玩完再吃" means START_TIME=10:00, DURATION_RANGE=180..240,
                MAX_END_TIME=14:00, ORDER_PREFERENCE=ACTIVITY_THEN_DINING.
                Use EXPLICIT only when the user turn directly says or clearly refers to the value.
                """;
    }

    public String userPayload(UnderstandingRequest request) {
        try {
            PendingAction pending = request == null ? null : request.pendingAction();
            List<CandidateSet> candidates = request == null || request.sessionState() == null
                    ? List.of() : request.sessionState().lastCandidates();
            List<RecentEvent> events = request == null || request.sessionState() == null
                    ? List.of() : request.sessionState().recentEvents();
            List<PlanStep> timeline = request == null || request.draft() == null
                    ? List.of() : request.draft().timeline();
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
