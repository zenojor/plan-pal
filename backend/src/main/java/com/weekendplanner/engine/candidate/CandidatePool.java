package com.weekendplanner.engine.candidate;

import java.util.List;
import java.util.Map;

public record CandidatePool(
        String planId,
        Map<String, List<CandidateProfile>> phaseCandidates,
        List<TaskStat> taskStats,
        List<String> degradationNotes
) {
    public CandidatePool {
        phaseCandidates = phaseCandidates == null ? Map.of() : Map.copyOf(phaseCandidates);
        taskStats = taskStats == null ? List.of() : List.copyOf(taskStats);
        degradationNotes = degradationNotes == null ? List.of() : List.copyOf(degradationNotes);
    }

    public List<CandidateProfile> candidatesFor(String phase) {
        return phaseCandidates.getOrDefault(phase, List.of());
    }

    public record TaskStat(
            String taskId,
            String phase,
            String category,
            int resultCount,
            long elapsedMs,
            boolean success,
            String note
    ) {}
}
