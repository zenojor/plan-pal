package com.weekendplanner.engine.candidate;

import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PoiDto;

import java.util.List;

public record CandidateProfile(
        PoiDto poi,
        String phase,
        double score,
        List<String> matchedTags,
        CheckResponse availability,
        List<String> sourceTaskIds,
        String rejectionReason
) {
    public CandidateProfile {
        matchedTags = matchedTags == null ? List.of() : List.copyOf(matchedTags);
        sourceTaskIds = sourceTaskIds == null ? List.of() : List.copyOf(sourceTaskIds);
    }

    public CandidateProfile withAvailability(CheckResponse availability, String rejectionReason) {
        return new CandidateProfile(poi, phase, score, matchedTags, availability, sourceTaskIds, rejectionReason);
    }
}
