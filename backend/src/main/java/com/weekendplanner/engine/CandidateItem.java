package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PoiDto;

public record CandidateItem(
        int index,
        PoiDto poi,
        PlanPatch planPatch
) {
}
