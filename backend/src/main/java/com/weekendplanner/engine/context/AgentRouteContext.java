package com.weekendplanner.engine.context;

import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.engine.candidate.CandidateSet;

import java.util.List;

public record AgentRouteContext(
        PendingAction pendingAction,
        List<CandidateSet> activeCandidates,
        String selectedSegmentId,
        ConstraintSet constraints,
        String patchPayload
) {
}
