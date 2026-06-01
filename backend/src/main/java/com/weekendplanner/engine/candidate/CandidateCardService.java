package com.weekendplanner.engine.candidate;

import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.PoiPreview;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.patch.PlanPatchFactory;
import com.weekendplanner.engine.planning.RenderTextService;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class CandidateCardService {

    private final ReplacementSearchEngine replacementSearchEngine;
    private final PlanPatchFactory patchFactory;
    private final AgentRuntimeProperties runtime;
    private final RenderTextService textService;

    public CandidateCardService(ReplacementSearchEngine replacementSearchEngine,
                                PlanPatchFactory patchFactory,
                                AgentRuntimeProperties runtime,
                                RenderTextService textService) {
        this.replacementSearchEngine = replacementSearchEngine;
        this.patchFactory = patchFactory;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
        this.textService = textService == null ? new RenderTextService() : textService;
    }

    public CandidateCardResult buildCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        if (replacementSearchEngine == null) {
            throw new IllegalStateException("ReplacementSearchEngine is required for candidate cards");
        }
        Optional<PlanStep> targetOpt = findTargetStep(draft, patch);
        boolean isAdd = "ADD".equalsIgnoreCase(patch.editType()) || targetOpt.isEmpty();
        String phase = targetOpt.isPresent()
                ? firstNonBlank(patch.target().activityType(), patch.target().phase(), targetOpt.get().phase())
                : firstNonBlank(patch.target().activityType(), patch.target().phase(), "ACTIVITY");

        Set<String> usedIds = new HashSet<>();
        draft.timeline().stream()
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .forEach(step -> usedIds.add(step.poiId()));

        List<PoiDto> candidates = replacementSearchEngine.findCandidates(
                phase, patch, draft.intent(), usedIds, runtime.getCandidateLimit());
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (PoiDto poi : candidates) {
            PlanPatch selectedPatch = isAdd
                    ? patchFactory.withSelectedPoiForAdd(patch, poi.poiId(), phase)
                    : patchFactory.withSelectedPoi(patch, targetOpt.get().segmentId(), poi.poiId(), phase);
            PoiPreview preview = new PoiPreview(poi.poiId(), poi.name(), poi.category(), poi.distanceKm(),
                    poi.tags(), poi.address(), poi.businessHours(), poi.telephone(), poi.source(), "merchant-placeholder");
            String optionId = (isAdd ? "add-poi-" : "replace-poi-") + poi.poiId();
            String targetSegmentId = isAdd ? null : targetOpt.get().segmentId();
            options.add(new ActionCard.ActionOption(optionId, textService.chooseLabel(poi, isAdd),
                    textService.candidateDescription(poi), "SUBMIT_PATCH", targetSegmentId, null,
                    selectedPatch, List.of(poi.poiId()), preview, "POI"));
            items.add(new CandidateItem(index, poi, selectedPatch));
            index++;
        }

        String targetSegmentId = isAdd ? null : targetOpt.map(PlanStep::segmentId).orElse(null);
        String candidateSetId = runtime.getCandidateIdPrefix() + draft.planId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        CandidateSet candidateSet = new CandidateSet(candidateSetId, phase, targetSegmentId, items, Instant.now());
        ActionCard card = new ActionCard(
                isAdd ? "add-candidates" : "replacement-candidates-" + (targetSegmentId == null ? "new" : targetSegmentId),
                textService.candidateCardTitle(isAdd),
                textService.candidateCardDescription(isAdd),
                options,
                null,
                false,
                "POI");
        return new CandidateCardResult(card, candidateSet);
    }

    public Optional<PlanStep> findTargetStep(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        if (patch == null || "ADD".equalsIgnoreCase(patch.editType())) return Optional.empty();
        String segmentId = patch.target().segmentId();
        if (segmentId != null && !segmentId.isBlank()) {
            Optional<PlanStep> matched = draft.timeline().stream()
                    .filter(step -> segmentId.equals(step.segmentId()) || segmentId.equals(step.poiId()))
                    .findFirst();
            if (matched.isPresent()) return matched;
        }
        return draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .findFirst();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
