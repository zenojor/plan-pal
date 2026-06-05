package com.weekendplanner.engine.understanding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class UnderstandingValidator {

    @Value("${agent.understanding.min-slot-confidence:0.60}")
    private double minSlotConfidence = 0.60;

    public TurnUnderstanding validate(TurnUnderstanding raw) {
        if (raw == null) return TurnUnderstanding.empty();
        Map<SlotName, SlotValue> accepted = new EnumMap<>(SlotName.class);
        raw.slots().forEach((name, value) -> {
            if (value != null && value.confidence() >= minSlotConfidence && isValid(value)) {
                accepted.put(name, value);
            }
        });
        boolean readOnly = raw.readOnlyQuestion() || raw.turnIntent() == TurnIntent.READ_ONLY_QUESTION;
        return new TurnUnderstanding(raw.turnIntent(), raw.domainIntent(), raw.routeTarget(), accepted, raw.missingSlots(),
                readOnly, raw.selectedCandidateIndex(), raw.confidence(), raw.reasonCode());
    }

    private boolean isValid(SlotValue value) {
        return switch (value.name()) {
            case HEADCOUNT -> value.value() instanceof Number
                    || (value.value() != null && String.valueOf(value.value()).matches("\\d+"));
            case START_TIME, END_TIME, MAX_END_TIME -> value.value() != null
                    && String.valueOf(value.value()).matches("\\d{1,2}:\\d{2}");
            case DURATION_RANGE -> value.minMinutes() != null && value.maxMinutes() != null
                    && value.minMinutes() > 0 && value.maxMinutes() >= value.minMinutes();
            default -> value.value() != null && !String.valueOf(value.value()).isBlank();
        };
    }
}
