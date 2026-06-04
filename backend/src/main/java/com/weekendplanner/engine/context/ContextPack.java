package com.weekendplanner.engine.context;

import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.engine.candidate.CandidateSet;

import java.util.List;

public record ContextPack(
        String userId,
        String planId,
        String userTurn,
        DraftDigest draft,
        String selectedSegmentId,
        PendingAction pendingAction,
        List<CandidateSet> activeCandidates,
        List<RecentEvent> recentEvents,
        ConstraintSet constraints,
        List<String> allowedTools,
        int contextVersion
) {
    public ContextPack {
        activeCandidates = activeCandidates == null ? List.of() : List.copyOf(activeCandidates);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        contextVersion = contextVersion <= 0 ? 1 : contextVersion;
    }
}
