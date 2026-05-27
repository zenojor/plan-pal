package com.weekendplanner.engine;

import com.weekendplanner.dto.ActionCard;

public record CandidateCardResult(
        ActionCard card,
        CandidateSet candidateSet
) {
}
