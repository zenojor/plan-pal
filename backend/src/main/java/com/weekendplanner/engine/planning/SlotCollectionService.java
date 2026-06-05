package com.weekendplanner.engine.planning;

import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.engine.context.PendingAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SlotCollectionService {

    public SlotCollectionPrompt forInitial(String planId, boolean missingTime, boolean missingHeadcount) {
        List<String> missing = new ArrayList<>();
        if (missingTime) missing.add("TIME_RANGE");
        if (missingHeadcount) missing.add("HEADCOUNT");
        return build(planId, missing);
    }

    public SlotCollectionPrompt forPending(String planId, PendingAction pending) {
        return build(planId, missingSlots(pending));
    }

    public SlotCollectionPrompt forSlots(String planId, List<String> missingSlots) {
        return build(planId, missingSlots);
    }

    public List<String> missingSlots(PendingAction pending) {
        if (pending == null) return List.of("TIME_RANGE", "HEADCOUNT");
        Map<String, Object> collected = pending.collectedSlots() == null ? Map.of() : pending.collectedSlots();
        Set<String> missing = new LinkedHashSet<>();
        for (String required : pending.requiredSlots()) {
            String normalized = normalizeSlot(required);
            if (!hasCollectedSlot(collected, normalized)) {
                missing.add(normalized);
            }
        }
        if (missing.isEmpty()) {
            if (!hasCollectedSlot(collected, "TIME_RANGE")) missing.add("TIME_RANGE");
            if (!hasCollectedSlot(collected, "HEADCOUNT")) missing.add("HEADCOUNT");
        }
        return List.copyOf(missing);
    }

    public SlotCollectionPrompt build(String planId, List<String> missingSlots) {
        List<String> normalized = normalizeSlots(missingSlots);
        String message = messageFor(normalized);
        List<ActionCard.ActionOption> options = new ArrayList<>();
        if (normalized.contains("TIME_RANGE")) {
            options.add(slotOption("slot-time-morning", "上午 10:00-12:30",
                    "适合轻量活动或早午餐。", "上午 10:00 到 12:30", "SLOT_TIME_RANGE"));
            options.add(slotOption("slot-time-afternoon", "下午 14:00-18:00",
                    "默认下午见面时使用这个完整时间段。", "下午 14:00 到 18:00", "SLOT_TIME_RANGE"));
            options.add(slotOption("slot-time-evening", "晚上 19:00-22:00",
                    "适合晚餐、电影或夜间轻活动。", "晚上 19:00 到 22:00", "SLOT_TIME_RANGE"));
        }
        if (normalized.contains("HEADCOUNT")) {
            options.add(slotOption("slot-headcount-1", "1 人", "一个人自由安排。", "总共 1 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-2", "2 人", "两个人一起。", "总共 2 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-3", "3 人", "三个人一起。", "总共 3 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-4", "4+ 人", "四个人或更多。", "总共 4 个人以上", "SLOT_HEADCOUNT"));
        }
        if (normalized.contains("LOCATION_SCOPE")) {
            options.add(slotOption("slot-location-nearby", "就近安排", "优先选附近、不绕路。", "就在附近安排，别太远", "SLOT_LOCATION_SCOPE"));
            options.add(slotOption("slot-location-business", "指定商圈/地铁站", "可以在输入框补充位置。", "我想指定一个商圈或地铁站", "SLOT_LOCATION_SCOPE"));
            options.add(slotOption("slot-location-flexible", "范围可以放宽", "找不到合适候选时可扩大范围。", "范围可以放宽一点", "SLOT_LOCATION_SCOPE"));
        }
        if (normalized.contains("PACE")) {
            options.add(slotOption("slot-pace-relaxed", "轻松一点", "少排队、少折腾。", "节奏轻松一点", "SLOT_PACE"));
            options.add(slotOption("slot-pace-normal", "正常安排", "时间利用和舒适度平衡。", "正常节奏安排", "SLOT_PACE"));
            options.add(slotOption("slot-pace-compact", "多安排一点", "可以更紧凑。", "可以多安排一点", "SLOT_PACE"));
        }
        if (normalized.contains("ORDER_PREFERENCE")) {
            options.add(slotOption("slot-order-activity-first", "先玩再吃", "活动结束后再去吃饭。", "先玩完再吃饭", "SLOT_ORDER_PREFERENCE"));
            options.add(slotOption("slot-order-dining-first", "先吃再玩", "先吃饭再安排活动。", "先吃饭再玩", "SLOT_ORDER_PREFERENCE"));
            options.add(slotOption("slot-order-flexible", "都可以", "由 PlanPal 按路线和时间安排。", "顺序都可以", "SLOT_ORDER_PREFERENCE"));
        }
        ActionCard card = new ActionCard("slot-collection-" + safePlanId(planId), "补充出行信息",
                "先补齐缺失项，我再继续安排。", options,
                "也可以直接输入时间、人数、地点或偏好", true, "SLOT_COLLECTION");
        return new SlotCollectionPrompt(message, card);
    }

    public List<String> missingFromIntent(PlanIntent intent) {
        List<String> missing = new ArrayList<>();
        if (intent == null || intent.startTime() == null || intent.startTime().isBlank()) {
            missing.add("TIME_RANGE");
        }
        if (intent == null || intent.headcount() <= 0) {
            missing.add("HEADCOUNT");
        }
        return missing;
    }

    private ActionCard.ActionOption slotOption(String id,
                                               String label,
                                               String description,
                                               String prompt,
                                               String optionKind) {
        return new ActionCard.ActionOption(id, label, description, "SET_SLOT", null, prompt,
                null, List.of(), null, optionKind);
    }

    private List<String> normalizeSlots(List<String> slots) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (slots != null) {
            for (String slot : slots) {
                String value = normalizeSlot(slot);
                if (!value.isBlank()) normalized.add(value);
            }
        }
        if (normalized.isEmpty()) normalized.add("TIME_RANGE");
        return List.copyOf(normalized);
    }

    private String normalizeSlot(String slot) {
        String value = slot == null ? "" : slot.trim();
        String key = value.replace("-", "_").toUpperCase(Locale.ROOT);
        return switch (key) {
            case "TIME", "TIMEWINDOW", "TIME_WINDOW", "STARTTIME", "START_TIME" -> "TIME_RANGE";
            case "HEADCOUNT", "HEAD_COUNT", "PEOPLE", "PERSON_COUNT" -> "HEADCOUNT";
            case "LOCATION", "LOCATIONSCOPE", "LOCATION_SCOPE", "AREA", "RANGE" -> "LOCATION_SCOPE";
            case "DURATION", "DURATION_RANGE", "TOTAL_MINUTES" -> "DURATION_RANGE";
            case "ORDER", "ORDERPREFERENCE", "ORDER_PREFERENCE" -> "ORDER_PREFERENCE";
            case "PACE" -> "PACE";
            case "BUDGET", "BUDGET_LEVEL" -> "BUDGET_LEVEL";
            default -> key;
        };
    }

    private boolean hasCollectedSlot(Map<String, Object> collected, String normalizedSlot) {
        if (collected == null || collected.isEmpty()) return false;
        return switch (normalizedSlot) {
            case "TIME_RANGE" -> present(collected, "timeWindow")
                    || present(collected, "timeRange")
                    || present(collected, "startTime")
                    || present(collected, "START_TIME")
                    || present(collected, "TIME_RANGE");
            case "HEADCOUNT" -> present(collected, "headcount") || present(collected, "HEADCOUNT");
            case "LOCATION_SCOPE" -> present(collected, "locationScope") || present(collected, "LOCATION_SCOPE");
            case "ORDER_PREFERENCE" -> present(collected, "orderPreference") || present(collected, "ORDER_PREFERENCE");
            case "PACE" -> present(collected, "pace") || present(collected, "PACE");
            default -> present(collected, normalizedSlot);
        };
    }

    private boolean present(Map<String, Object> collected, String key) {
        Object value = collected.get(key);
        if (value == null) return false;
        return !(value instanceof String text) || !text.isBlank();
    }

    private String messageFor(List<String> missing) {
        if (missing.contains("TIME_RANGE") && missing.contains("HEADCOUNT")) {
            return "先选一下出行时间，再告诉我几个人去。";
        }
        if (missing.contains("TIME_RANGE")) return "先选一下出行时间。";
        if (missing.contains("HEADCOUNT")) return "几个人一起去？";
        if (missing.contains("LOCATION_SCOPE")) return "想在哪附近安排？";
        if (missing.contains("PACE")) return "想轻松一点，还是多安排一点？";
        if (missing.contains("ORDER_PREFERENCE")) return "想先玩再吃，还是先吃再玩？";
        return "还差一点信息，补一下我再继续安排。";
    }

    private String safePlanId(String planId) {
        return planId == null || planId.isBlank() ? "pending" : planId;
    }

    public record SlotCollectionPrompt(String message, ActionCard card) {
    }
}
