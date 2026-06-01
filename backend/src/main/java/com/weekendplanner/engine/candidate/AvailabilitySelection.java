package com.weekendplanner.engine.candidate;

import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PoiDto;

import java.util.List;

public record AvailabilitySelection(
        PoiDto poi,
        CheckResponse availability,
        boolean degraded,
        String degradationNote,
        List<CandidateProfile> checkedCandidates
) {
    public AvailabilitySelection {
        checkedCandidates = checkedCandidates == null ? List.of() : List.copyOf(checkedCandidates);
    }

    public static AvailabilitySelection none(String note, List<CandidateProfile> checkedCandidates) {
        return new AvailabilitySelection(null, null, true, note, checkedCandidates);
    }
}
