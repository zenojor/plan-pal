package com.weekendplanner.engine.candidate;

import com.weekendplanner.dto.ActionCard;

public record CandidateCardResult(
        ActionCard card,
        CandidateSet candidateSet
) {
}
