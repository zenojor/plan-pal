package com.weekendplanner.dto;

import java.util.List;

public record ActionCard(
        String id,
        String title,
        String description,
        List<ActionOption> options,
        String inputPlaceholder,
        boolean allowCustomInput,
        String cardKind
) {
    public ActionCard {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public ActionCard(String id,
                      String title,
                      String description,
                      List<ActionOption> options,
                      String inputPlaceholder,
                      boolean allowCustomInput) {
        this(id, title, description, options, inputPlaceholder, allowCustomInput, null);
    }

    public record ActionOption(
            String id,
            String label,
            String description,
            String actionType,
            String targetSegmentId,
            String prompt,
            PlanPatch planPatch,
            List<String> poiIds,
            PoiPreview poiPreview,
            String optionKind,
            Double score,
            List<String> decisionReasons,
            List<String> matchedTags,
            List<String> tradeoffs,
            MovieScreening screening
    ) {
        public ActionOption {
            poiIds = poiIds == null ? List.of() : List.copyOf(poiIds);
            decisionReasons = decisionReasons == null ? List.of() : List.copyOf(decisionReasons);
            matchedTags = matchedTags == null ? List.of() : List.copyOf(matchedTags);
            tradeoffs = tradeoffs == null ? List.of() : List.copyOf(tradeoffs);
        }

        public ActionOption(String id,
                            String label,
                            String description,
                            String actionType,
                            String targetSegmentId,
                            String prompt,
                            PlanPatch planPatch,
                            List<String> poiIds,
                            PoiPreview poiPreview) {
            this(id, label, description, actionType, targetSegmentId, prompt, planPatch, poiIds, poiPreview, null);
        }

        public ActionOption(String id,
                            String label,
                            String description,
                            String actionType,
                            String targetSegmentId,
                            String prompt,
                            PlanPatch planPatch,
                            List<String> poiIds) {
            this(id, label, description, actionType, targetSegmentId, prompt, planPatch, poiIds, null, null);
        }

        public ActionOption(String id,
                            String label,
                            String description,
                            String actionType,
                            String targetSegmentId,
                            String prompt,
                            PlanPatch planPatch,
                            List<String> poiIds,
                            PoiPreview poiPreview,
                            String optionKind) {
            this(id, label, description, actionType, targetSegmentId, prompt, planPatch, poiIds, poiPreview,
                    optionKind, null, List.of(), List.of(), List.of(), null);
        }

        public ActionOption withDecision(Double score,
                                         List<String> decisionReasons,
                                         List<String> matchedTags,
                                         List<String> tradeoffs) {
            return new ActionOption(id, label, description, actionType, targetSegmentId, prompt, planPatch,
                    poiIds, poiPreview, optionKind, score, decisionReasons, matchedTags, tradeoffs, screening);
        }

        public ActionOption withScreening(MovieScreening screening) {
            return new ActionOption(id, label, description, actionType, targetSegmentId, prompt, planPatch,
                    poiIds, poiPreview, optionKind, score, decisionReasons, matchedTags, tradeoffs, screening);
        }
    }

    public record MovieScreening(
            String screeningId,
            String movieId,
            String movieTitle,
            String cinemaId,
            String cinemaName,
            String startTime,
            String endTime,
            String hall,
            String format,
            String language,
            double pricePerTicket,
            int remainingSeats
    ) {}
}
