package com.weekendplanner.engine.search;

import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.understanding.DomainIntent;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.SlotValue;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class SearchIntentMapper {

    public Optional<CandidateSearchRefinement> refinementFrom(ContextPack context, TurnUnderstanding understanding) {
        if (context == null || context.userTurn() == null || context.userTurn().isBlank()) {
            return Optional.empty();
        }
        PendingAction pending = context.pendingAction();
        if (!isCandidateDecisionPending(pending)) {
            return Optional.empty();
        }

        String text = normalize(context.userTurn());
        LinkedHashSet<String> include = new LinkedHashSet<>();
        LinkedHashSet<String> exclude = new LinkedHashSet<>();
        String category = null;
        String domain = null;
        String budget = null;
        String locationScope = null;
        boolean expandRange = false;

        if (understanding != null) {
            Optional<String> searchTag = understanding.slot(SlotName.SEARCH_TAG).map(SlotValue::value)
                    .map(String::valueOf)
                    .map(this::normalizeTag)
                    .filter(value -> !value.isBlank());
            searchTag.ifPresent(include::add);
            Optional<String> searchCategory = understanding.slot(SlotName.SEARCH_CATEGORY).map(SlotValue::value)
                    .map(String::valueOf)
                    .map(value -> value.toUpperCase(Locale.ROOT));
            if (searchCategory.isPresent()) {
                category = categoryFrom(searchCategory.get());
                domain = domainFromCategory(category);
            } else if (understanding.domainIntent() == DomainIntent.DINING
                    || understanding.domainIntent() == DomainIntent.DINING_LOCKED_PLAN) {
                category = "RESTAURANT";
                domain = "DINING";
            } else if (understanding.domainIntent() == DomainIntent.PRODUCT) {
                category = "PRODUCT";
                domain = "PRODUCT";
            } else if (understanding.domainIntent() == DomainIntent.ACTIVITY) {
                category = "ACTIVITY";
                domain = "ACTIVITY";
            }
        }

        if (containsAny(text, "\u706b\u9505", "hotpot", "\u6dae\u9505")) {
            include.add("hotpot");
            category = "RESTAURANT";
            domain = "DINING";
        }
        if (containsAny(text, "\u70e7\u70e4", "\u70e4\u8089", "bbq", "barbecue")) {
            include.add("bbq");
            category = "RESTAURANT";
            domain = "DINING";
        }
        if (containsAny(text, "\u5ddd\u83dc", "\u6e58\u83dc", "\u5403\u8fa3", "\u8fa3", "spicy", "sichuan", "hunan")) {
            include.add("spicy");
            category = "RESTAURANT";
            domain = "DINING";
        }
        if (containsAny(text, "\u5c0f\u9f99\u867e", "crayfish")) {
            include.add("crayfish");
            category = "RESTAURANT";
            domain = "DINING";
        }
        if (containsAny(text, "\u5976\u8336", "milk tea", "bubble tea", "\u996e\u54c1", "\u597d\u559d")) {
            include.add("tea");
            category = "PRODUCT";
            domain = "PRODUCT";
        }
        if (containsAny(text, "\u51b0\u6c99", "\u679c\u6c41", "smoothie", "juice")) {
            include.add("smoothie");
            category = "PRODUCT";
            domain = "PRODUCT";
        }
        if (containsAny(text, "\u5496\u5561", "coffee")) {
            include.add("coffee");
            category = "PRODUCT";
            domain = "PRODUCT";
        }
        if (containsAny(text, "\u751c\u54c1", "dessert")) {
            include.add("dessert");
            category = firstNonBlank(category, "PRODUCT");
            domain = firstNonBlank(domain, "PRODUCT");
        }
        if (containsAny(text, "\u5ba4\u5185", "\u4e0d\u8981\u6237\u5916", "indoor")) {
            include.add("indoor");
            category = firstNonBlank(category, "ACTIVITY");
            domain = firstNonBlank(domain, "ACTIVITY");
        }
        if (containsAny(text, "\u4eb2\u5b50", "\u5b69\u5b50", "\u5c0f\u5b69", "child", "kid")) {
            include.add("child_friendly");
            category = firstNonBlank(category, "ACTIVITY");
            domain = firstNonBlank(domain, "ACTIVITY");
        }
        if (containsAny(text, "\u5b89\u9759", "\u5b89\u9759\u4e00\u70b9", "quiet")) {
            include.add("quiet");
        }
        if (containsAny(text, "\u516c\u56ed", "\u6563\u6b65", "park", "walk")) {
            include.add("park");
            category = firstNonBlank(category, "ACTIVITY");
            domain = firstNonBlank(domain, "ACTIVITY");
        }
        if (containsAny(text, "\u4e0d\u8981\u5546\u573a", "\u522b\u5728\u5546\u573a", "no mall", "not mall")) {
            exclude.add("mall");
        }
        if (containsAny(text, "\u9644\u8fd1", "\u8fd1\u4e00\u70b9", "\u522b\u592a\u8fdc", "\u592a\u8fdc", "nearby", "nearer", "too far")) {
            locationScope = "NEARBY";
        }
        if (containsAny(text, "\u4fbf\u5b9c", "\u7701\u94b1", "\u9884\u7b97\u4f4e", "cheap", "budget")) {
            budget = "LOW";
        }
        if (containsAny(text, "\u6269\u5927\u8303\u56f4", "\u591a\u627e\u51e0\u4e2a", "\u66f4\u591a", "expand", "more options")) {
            expandRange = true;
        }

        if (category == null && !include.isEmpty()) {
            category = "ACTIVITY";
            domain = "ACTIVITY";
        }
        CandidateSearchRefinement refinement = new CandidateSearchRefinement(
                pending.candidateSetId(),
                pending.targetSegmentId(),
                domain,
                category,
                List.copyOf(include),
                List.copyOf(exclude),
                budget,
                locationScope,
                expandRange);
        boolean semanticRefinement = !include.isEmpty() || !exclude.isEmpty() || category != null || expandRange;
        return semanticRefinement && refinement.hasMeaningfulConstraint() ? Optional.of(refinement) : Optional.empty();
    }

    public Map<String, Object> toCommandSlots(CandidateSearchRefinement refinement) {
        if (refinement == null) return Map.of();
        Map<String, Object> slots = new LinkedHashMap<>();
        if (!refinement.includeTags().isEmpty()) {
            slots.put("includeTags", refinement.includeTags());
            slots.put("strictTags", true);
        }
        if (!refinement.excludeTags().isEmpty()) {
            slots.put("excludeTags", refinement.excludeTags());
        }
        if (notBlank(refinement.category())) {
            slots.put("category", refinement.category());
            slots.put("phase", phaseFromCategory(refinement.category()));
        }
        if (notBlank(refinement.budgetLevel())) {
            slots.put("budgetLevel", refinement.budgetLevel());
        }
        if ("NEARBY".equalsIgnoreCase(refinement.locationScope())) {
            slots.put("distancePreference", "nearer");
        }
        if (refinement.expandRange()) {
            slots.put("expandRange", true);
        }
        return Map.copyOf(slots);
    }

    private String categoryFrom(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DINING", "RESTAURANT", "FOOD" -> "RESTAURANT";
            case "PRODUCT", "FOOD_DRINK_PRODUCT", "DRINK", "DRINKS" -> "PRODUCT";
            case "ACTIVITY", "LEISURE", "POI" -> "ACTIVITY";
            default -> normalized;
        };
    }

    private String phaseFromCategory(String category) {
        if ("RESTAURANT".equalsIgnoreCase(category) || "PRODUCT".equalsIgnoreCase(category)) return "DINING";
        return "ACTIVITY";
    }

    private String domainFromCategory(String category) {
        if ("PRODUCT".equalsIgnoreCase(category)) return "PRODUCT";
        if ("RESTAURANT".equalsIgnoreCase(category)) return "DINING";
        if ("ACTIVITY".equalsIgnoreCase(category)) return "ACTIVITY";
        return null;
    }

    private String normalizeTag(String value) {
        String normalized = normalize(value).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "hot_pot" -> "hotpot";
            case "milk_tea", "bubble_tea" -> "tea";
            case "drink", "drinks" -> "tea";
            default -> normalized;
        };
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) return value;
        }
        return null;
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isCandidateDecisionPending(PendingAction pending) {
        if (pending == null || pending.type() == null) return false;
        return switch (pending.type().toUpperCase(Locale.ROOT)) {
            case "SELECT_CANDIDATE", "REPLACE_SEGMENT", "QUEUE_REPAIR", "PRODUCT_RESEARCH", "PLAN_CHOICE" -> true;
            default -> false;
        };
    }
}
