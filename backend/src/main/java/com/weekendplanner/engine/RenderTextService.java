package com.weekendplanner.engine;

import com.weekendplanner.dto.PoiDto;
import org.springframework.stereotype.Component;

@Component
public class RenderTextService {

    public String fastWorkflowStarted() {
        return "Fast workflow started";
    }

    public String planUpdated() {
        return "Plan updated";
    }

    public String pendingCancelled() {
        return "Pending selection cancelled.";
    }

    public String clarificationFallback() {
        return "Please add one more preference so I can continue.";
    }

    public String candidatePrompt() {
        return "Please choose one candidate.";
    }

    public String autoRecommendationSuffix() {
        return "\n\nI found a few options for the new open slot. Pick one and I will update the plan.";
    }

    public String candidateCardTitle(boolean isAdd) {
        return isAdd ? "Choose a new place" : "Choose a replacement";
    }

    public String candidateCardDescription(boolean isAdd) {
        return isAdd
                ? "Pick one candidate and I will add it into the plan."
                : "Pick one candidate and I will replace the target segment.";
    }

    public String chooseLabel(PoiDto poi, boolean isAdd) {
        String verb = isAdd ? "Add " : "Choose ";
        return verb + (poi == null ? "candidate" : poi.name());
    }

    public String candidateDescription(PoiDto poi) {
        if (poi == null) return "";
        String tags = poi.tags() == null || poi.tags().isEmpty() ? "" : " / " + String.join("/", poi.tags());
        return String.format(java.util.Locale.ROOT, "%.1fkm%s", poi.distanceKm(), tags);
    }
}
