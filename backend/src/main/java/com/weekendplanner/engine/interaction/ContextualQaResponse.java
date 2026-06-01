package com.weekendplanner.engine.interaction;

import com.weekendplanner.dto.ActionCard;

public record ContextualQaResponse(
        String answer,
        ActionCard actionCard
) {
}
