package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.ActionCard;

import java.util.List;
import java.util.UUID;

public class ChoiceBarTool {

    public ActionCard renderChoiceBar(ChoiceBarSpec spec) {
        List<ActionCard.ActionOption> options = spec.options() == null ? List.of() : spec.options().stream()
                .map(option -> new ActionCard.ActionOption(
                        option.id() == null || option.id().isBlank()
                                ? "pref-" + UUID.randomUUID().toString().substring(0, 6)
                                : option.id(),
                        option.label(),
                        option.description() == null ? "" : option.description(),
                        option.actionType() == null || option.actionType().isBlank()
                                ? "SELECT_PREFERENCE"
                                : option.actionType(),
                        null,
                        option.prompt(),
                        null,
                        List.of(),
                        null,
                        "PREFERENCE"))
                .toList();
        return new ActionCard(
                spec.id() == null || spec.id().isBlank() ? "consult-choice-" + UUID.randomUUID().toString().substring(0, 8) : spec.id(),
                spec.title(),
                spec.description(),
                options,
                spec.inputPlaceholder(),
                spec.allowCustomInput(),
                "PREFERENCE");
    }

    public record ChoiceBarSpec(
            String id,
            String title,
            String description,
            List<ChoiceBarOption> options,
            String inputPlaceholder,
            boolean allowCustomInput
    ) {
    }

    public record ChoiceBarOption(
            String id,
            String label,
            String description,
            String actionType,
            String prompt
    ) {
    }
}
