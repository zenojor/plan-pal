package com.weekendplanner.engine.understanding;

public record SlotValue(
        SlotName name,
        Object value,
        Integer minMinutes,
        Integer maxMinutes,
        SlotProvenance provenance,
        double confidence,
        String sourceText
) {
    public SlotValue {
        provenance = provenance == null ? SlotProvenance.EXPLICIT : provenance;
        confidence = confidence <= 0 ? 1.0 : Math.min(1.0, confidence);
        sourceText = sourceText == null ? "" : sourceText;
    }

    public static SlotValue of(SlotName name, Object value, SlotProvenance provenance, double confidence, String sourceText) {
        return new SlotValue(name, value, null, null, provenance, confidence, sourceText);
    }

    public static SlotValue durationRange(Integer minMinutes,
                                          Integer maxMinutes,
                                          SlotProvenance provenance,
                                          double confidence,
                                          String sourceText) {
        return new SlotValue(SlotName.DURATION_RANGE, null, minMinutes, maxMinutes, provenance, confidence, sourceText);
    }
}
