package com.weekendplanner.engine.context;



import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.PlanStep;

import java.time.Instant;
import java.util.List;

public record SessionState(
        String sessionId,
        String planId,
        String userId,
        List<PlanStep> currentPlan,
        List<CandidateSet> lastCandidates,
        PendingAction pendingAction,
        ConstraintSet userConstraints,
        List<RecentEvent> recentEvents,
        List<String> lockedSegments,
        Instant updatedAt
) {
    public SessionState {
        currentPlan = currentPlan == null ? List.of() : List.copyOf(currentPlan);
        lastCandidates = lastCandidates == null ? List.of() : List.copyOf(lastCandidates);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        lockedSegments = lockedSegments == null ? List.of() : List.copyOf(lockedSegments);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
