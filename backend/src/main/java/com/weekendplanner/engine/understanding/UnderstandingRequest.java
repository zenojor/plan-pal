package com.weekendplanner.engine.understanding;

import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;

import java.util.List;

public record UnderstandingRequest(
        String userTurn,
        PendingAction pendingAction,
        List<CandidateSet> activeCandidates,
        List<PlanStep> timeline,
        List<RecentEvent> recentEvents,
        String source
) {
    public UnderstandingRequest {
        userTurn = userTurn == null ? "" : userTurn;
        activeCandidates = activeCandidates == null ? List.of() : List.copyOf(activeCandidates);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        source = source == null ? "" : source;
    }

    public static UnderstandingRequest fromContextPack(ContextPack contextPack, String source) {
        if (contextPack == null) {
            return new UnderstandingRequest("", null, List.of(), List.of(), List.of(), source);
        }
        return new UnderstandingRequest(
                contextPack.userTurn(),
                contextPack.pendingAction(),
                contextPack.activeCandidates(),
                contextPack.draft() == null ? List.of() : contextPack.draft().timeline(),
                contextPack.recentEvents(),
                source);
    }
}
