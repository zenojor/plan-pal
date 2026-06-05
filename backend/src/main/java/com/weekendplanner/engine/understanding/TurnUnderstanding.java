package com.weekendplanner.engine.understanding;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record TurnUnderstanding(
        TurnIntent turnIntent,
        DomainIntent domainIntent,
        RouteTarget routeTarget,
        Map<SlotName, SlotValue> slots,
        List<SlotName> missingSlots,
        boolean readOnlyQuestion,
        Integer selectedCandidateIndex,
        double confidence,
        String reasonCode
) {
    public TurnUnderstanding(TurnIntent turnIntent,
                             DomainIntent domainIntent,
                             Map<SlotName, SlotValue> slots,
                             List<SlotName> missingSlots,
                             boolean readOnlyQuestion,
                             Integer selectedCandidateIndex,
                             double confidence,
                             String reasonCode) {
        this(turnIntent, domainIntent, RouteTarget.UNKNOWN, slots, missingSlots, readOnlyQuestion,
                selectedCandidateIndex, confidence, reasonCode);
    }

    public TurnUnderstanding {
        turnIntent = turnIntent == null ? TurnIntent.UNKNOWN : turnIntent;
        domainIntent = domainIntent == null ? DomainIntent.UNKNOWN : domainIntent;
        routeTarget = routeTarget == null ? RouteTarget.UNKNOWN : routeTarget;
        slots = slots == null || slots.isEmpty() ? Map.of() : Map.copyOf(slots);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public Optional<SlotValue> slot(SlotName name) {
        return Optional.ofNullable(slots.get(name));
    }

    public boolean hasSlots() {
        return !slots.isEmpty();
    }

    public static TurnUnderstanding empty() {
        return new TurnUnderstanding(TurnIntent.UNKNOWN, DomainIntent.UNKNOWN, RouteTarget.UNKNOWN, Map.of(), List.of(),
                false, null, 0.0, "empty");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TurnIntent turnIntent = TurnIntent.UNKNOWN;
        private DomainIntent domainIntent = DomainIntent.UNKNOWN;
        private RouteTarget routeTarget = RouteTarget.UNKNOWN;
        private final Map<SlotName, SlotValue> slots = new EnumMap<>(SlotName.class);
        private List<SlotName> missingSlots = List.of();
        private boolean readOnlyQuestion;
        private Integer selectedCandidateIndex;
        private double confidence = 1.0;
        private String reasonCode = "";

        public Builder turnIntent(TurnIntent value) {
            this.turnIntent = value;
            return this;
        }

        public Builder domainIntent(DomainIntent value) {
            this.domainIntent = value;
            return this;
        }

        public Builder routeTarget(RouteTarget value) {
            this.routeTarget = value;
            return this;
        }

        public Builder slot(SlotValue value) {
            if (value != null && value.name() != null) {
                this.slots.put(value.name(), value);
            }
            return this;
        }

        public Builder slots(Map<SlotName, SlotValue> values) {
            if (values != null) values.values().forEach(this::slot);
            return this;
        }

        public Builder missingSlots(List<SlotName> values) {
            this.missingSlots = values;
            return this;
        }

        public Builder readOnlyQuestion(boolean value) {
            this.readOnlyQuestion = value;
            return this;
        }

        public Builder selectedCandidateIndex(Integer value) {
            this.selectedCandidateIndex = value;
            return this;
        }

        public Builder confidence(double value) {
            this.confidence = value;
            return this;
        }

        public Builder reasonCode(String value) {
            this.reasonCode = value;
            return this;
        }

        public TurnUnderstanding build() {
            return new TurnUnderstanding(turnIntent, domainIntent, routeTarget, slots, missingSlots, readOnlyQuestion,
                    selectedCandidateIndex, confidence, reasonCode);
        }
    }
}
