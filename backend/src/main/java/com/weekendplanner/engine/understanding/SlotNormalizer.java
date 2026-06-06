package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class SlotNormalizer {

    public TurnUnderstanding fromJson(JsonNode node) {
        if (node == null || !node.isObject()) return TurnUnderstanding.empty();
        Map<SlotName, SlotValue> slots = new EnumMap<>(SlotName.class);
        JsonNode slotArray = node.path("slots");
        if (slotArray.isArray()) {
            for (JsonNode item : slotArray) {
                parseSlot(item).ifPresent(value -> slots.put(value.name(), value));
            }
        } else if (slotArray.isObject()) {
            slotArray.fields().forEachRemaining(entry -> parseNamedSlot(entry.getKey(), entry.getValue())
                    .ifPresent(value -> slots.put(value.name(), value)));
        }

        return new TurnUnderstanding(
                enumOr(node.path("turnIntent").asText(""), TurnIntent.class, TurnIntent.UNKNOWN),
                enumOr(node.path("domainIntent").asText(""), DomainIntent.class, DomainIntent.UNKNOWN),
                enumOr(node.path("routeTarget").asText(""), RouteTarget.class, RouteTarget.UNKNOWN),
                slots,
                parseMissingSlots(node.path("missingSlots")),
                node.path("readOnlyQuestion").asBoolean(false),
                node.has("selectedCandidateIndex") && !node.path("selectedCandidateIndex").isNull()
                        ? node.path("selectedCandidateIndex").asInt() : null,
                node.path("confidence").asDouble(1.0),
                node.path("reasonCode").asText("")
        );
    }

    public Map<String, Object> toPendingSlotMap(TurnUnderstanding understanding) {
        if (understanding == null || understanding.slots().isEmpty()) return Map.of();
        java.util.LinkedHashMap<String, Object> slots = new java.util.LinkedHashMap<>();
        for (SlotName name : SlotName.values()) {
            putPendingSlot(slots, understanding.slots().get(name));
        }
        return Map.copyOf(slots);
    }

    private Optional<SlotValue> parseSlot(JsonNode item) {
        if (item == null || !item.isObject()) return Optional.empty();
        SlotName name = enumOr(item.path("name").asText(""), SlotName.class, null);
        if (name == null) return Optional.empty();
        return Optional.of(slotValue(name, item));
    }

    private Optional<SlotValue> parseNamedSlot(String rawName, JsonNode item) {
        SlotName name = enumOr(rawName, SlotName.class, null);
        if (name == null) return Optional.empty();
        if (item != null && item.isObject()) return Optional.of(slotValue(name, item));
        return Optional.of(SlotValue.of(name, scalar(item), SlotProvenance.EXPLICIT, 1.0, ""));
    }

    private SlotValue slotValue(SlotName name, JsonNode item) {
        SlotProvenance provenance = enumOr(item.path("provenance").asText(""), SlotProvenance.class, SlotProvenance.EXPLICIT);
        double confidence = item.path("confidence").asDouble(1.0);
        String sourceText = item.path("sourceText").asText("");
        if (name == SlotName.DURATION_RANGE) {
            Integer min = intOrNull(item, "minMinutes");
            Integer max = intOrNull(item, "maxMinutes");
            if (min == null && item.has("value") && item.path("value").isObject()) {
                min = intOrNull(item.path("value"), "minMinutes");
                max = intOrNull(item.path("value"), "maxMinutes");
            }
            return SlotValue.durationRange(min, max == null ? min : max, provenance, confidence, sourceText);
        }
        Object value = item.has("value") ? scalar(item.path("value")) : scalar(item);
        return SlotValue.of(name, value, provenance, confidence, sourceText);
    }

    private void putPendingSlot(Map<String, Object> slots, SlotValue value) {
        if (value == null || value.name() == null) return;
        switch (value.name()) {
            case HEADCOUNT -> put(slots, "headcount", asInteger(value.value()), value.provenance());
            case START_TIME -> put(slots, "startTime", asString(value.value()), value.provenance());
            case END_TIME -> put(slots, "endTime", asString(value.value()), value.provenance());
            case TIME_RANGE -> putTimeRange(slots, value);
            case MAX_END_TIME -> {
                String text = asString(value.value());
                put(slots, "maxEndTime", text, value.provenance());
                put(slots, "endTime", text, value.provenance());
            }
            case LOCATION_SCOPE -> put(slots, "locationScope", upper(value.value()), value.provenance());
            case ORDER_PREFERENCE -> put(slots, "orderPreference", upper(value.value()), value.provenance());
            case PACE -> put(slots, "pace", upper(value.value()), value.provenance());
            case BUDGET_LEVEL -> put(slots, "budgetLevel", upper(value.value()), value.provenance());
            case TRANSPORT_MODE -> put(slots, "preferredTransportMode", upper(value.value()), value.provenance());
            case SEARCH_TAG -> put(slots, "searchTag", lower(value.value()), value.provenance());
            case SEARCH_CATEGORY -> put(slots, "searchCategory", upper(value.value()), value.provenance());
            case DURATION_RANGE -> {
                Integer min = value.minMinutes();
                Integer max = value.maxMinutes() == null ? min : value.maxMinutes();
                put(slots, "minDurationMinutes", min, value.provenance());
                put(slots, "maxDurationMinutes", max, value.provenance());
                put(slots, "durationMinutes", max, value.provenance());
                String start = asString(slots.get("startTime"));
                if (start != null && max != null) {
                    put(slots, "maxEndTime", addMinutes(start, max), value.provenance());
                    put(slots, "endTime", addMinutes(start, max), value.provenance());
                }
            }
        }
    }

    private void put(Map<String, Object> slots, String key, Object rawValue, SlotProvenance provenance) {
        if (rawValue == null) return;
        if (rawValue instanceof String text && text.isBlank()) return;
        slots.put(key, rawValue);
        if (provenance == SlotProvenance.EXPLICIT) {
            slots.put("explicit:" + key, true);
        } else if (provenance != null) {
            slots.put(provenance.name().toLowerCase(Locale.ROOT) + ":" + key, true);
        }
    }

    private void putTimeRange(Map<String, Object> slots, SlotValue value) {
        String range = upper(value.value());
        put(slots, "timeRange", range, value.provenance());
        if (range == null) return;
        switch (range) {
            case "MORNING" -> putConcreteTimeWindow(slots, "10:00", "12:30", 150, value.provenance());
            case "NOON" -> putConcreteTimeWindow(slots, "12:00", "14:00", 120, value.provenance());
            case "AFTERNOON" -> putConcreteTimeWindow(slots, "14:00", "18:00", 240, value.provenance());
            case "EVENING", "NIGHT" -> putConcreteTimeWindow(slots, "19:00", "22:00", 180, value.provenance());
            default -> {
            }
        }
    }

    private void putConcreteTimeWindow(Map<String, Object> slots,
                                       String startTime,
                                       String endTime,
                                       int durationMinutes,
                                       SlotProvenance provenance) {
        put(slots, "startTime", startTime, provenance);
        put(slots, "endTime", endTime, provenance);
        put(slots, "durationMinutes", durationMinutes, provenance);
        put(slots, "maxEndTime", endTime, provenance);
    }

    private List<SlotName> parseMissingSlots(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<SlotName> values = new ArrayList<>();
        node.forEach(item -> {
            SlotName parsed = enumOr(item.asText(""), SlotName.class, null);
            if (parsed != null) values.add(parsed);
        });
        return List.copyOf(values);
    }

    private Object scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isInt() || node.isLong()) return node.asInt();
        if (node.isDouble() || node.isFloat() || node.isNumber()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.path(field).isNull()) return null;
        return node.path(field).asInt();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String asString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String upper(Object value) {
        String text = asString(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private String lower(Object value) {
        String text = asString(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String addMinutes(String time, int minutes) {
        String[] parts = time.split(":");
        int total = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]) + minutes;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private <E extends Enum<E>> E enumOr(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
