package com.weekendplanner.engine.context;

import com.weekendplanner.engine.candidate.CandidateSet;

import java.util.List;

public record QaContext(
        String timelineSummary,
        List<CandidateSet> activeCandidates,
        List<RecentEvent> recentEvents,
        List<String> allowedTools
) {
}
