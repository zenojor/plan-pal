package com.weekendplanner.engine.workflow;

import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.candidate.CandidateCardService;
import com.weekendplanner.engine.candidate.CandidateItem;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.engine.context.RecentEventType;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

class CandidateChainWorkflow {

    private static final String DINING_THEN_DRINKS = "DINING_THEN_DRINKS";

    private final PlanExecutionStore executionStore;
    private final SessionStateStore sessionStateStore;
    private final PlanEditorEngine planEditorEngine;
    private final CandidateCardService candidateCardService;
    private final ReplacementSearchEngine replacementSearchEngine;
    private final AgentRuntimeProperties runtime;

    CandidateChainWorkflow(PlanExecutionStore executionStore,
                           SessionStateStore sessionStateStore,
                           PlanEditorEngine planEditorEngine,
                           CandidateCardService candidateCardService,
                           ReplacementSearchEngine replacementSearchEngine,
                           AgentRuntimeProperties runtime) {
        this.executionStore = executionStore;
        this.sessionStateStore = sessionStateStore;
        this.planEditorEngine = planEditorEngine;
        this.candidateCardService = candidateCardService;
        this.replacementSearchEngine = replacementSearchEngine;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    boolean tryHandleCandidate(ContextPack context,
                               PendingAction pending,
                               CandidateSet candidateSet,
                               CandidateItem item,
                               Consumer<SseEvent> emitter) {
        if (shouldAdvanceDiningDrinksChain(pending, candidateSet)) {
            emitDrinksCandidatesAfterDiningSelection(context, pending, item, emitter);
            return true;
        }
        if (shouldCompleteDiningDrinksChain(pending, candidateSet)) {
            completeDiningDrinksCandidateChain(context, pending, item, emitter);
            return true;
        }
        return false;
    }

    boolean tryHandlePatch(ContextPack context,
                           PendingAction pending,
                           PlanPatch patch,
                           Consumer<SseEvent> emitter) {
        if (shouldAdvanceDiningDrinksChain(pending, patch)) {
            emitDrinksCandidatesAfterDiningSelection(context, pending, candidateItemFromPatch(context, patch), emitter);
            return true;
        }
        if (shouldCompleteDiningDrinksChain(pending, patch)) {
            completeDiningDrinksCandidateChain(context, pending, candidateItemFromPatch(context, patch), emitter);
            return true;
        }
        return false;
    }

    private boolean shouldAdvanceDiningDrinksChain(PendingAction pending, CandidateSet candidateSet) {
        return isDiningDrinksChain(pending)
                && pending.selectedPatch() == null
                && candidateSet != null
                && "DINING".equalsIgnoreCase(candidateSet.type());
    }

    private boolean shouldAdvanceDiningDrinksChain(PendingAction pending, PlanPatch patch) {
        return isDiningDrinksChain(pending)
                && pending.selectedPatch() == null
                && isDiningPatch(patch);
    }

    private boolean shouldCompleteDiningDrinksChain(PendingAction pending, CandidateSet candidateSet) {
        return isDiningDrinksChain(pending)
                && pending.selectedPatch() != null
                && candidateSet != null
                && "DRINKS".equalsIgnoreCase(candidateSet.type());
    }

    private boolean shouldCompleteDiningDrinksChain(PendingAction pending, PlanPatch patch) {
        return isDiningDrinksChain(pending)
                && pending.selectedPatch() != null
                && isDrinksPatch(patch);
    }

    private boolean isDiningDrinksChain(PendingAction pending) {
        if (pending == null || pending.collectedSlots() == null) return false;
        return DINING_THEN_DRINKS.equals(String.valueOf(pending.collectedSlots().get("candidateChain")));
    }

    private CandidateItem candidateItemFromPatch(ContextPack context, PlanPatch patch) {
        String poiId = selectedPoiIds(patch).stream().findFirst().orElse("");
        if (context != null && !poiId.isBlank()) {
            for (CandidateSet set : context.activeCandidates()) {
                for (CandidateItem item : set.items()) {
                    if (item.poi() != null && poiId.equals(item.poi().poiId())) {
                        return new CandidateItem(item.index(), item.poi(), patch);
                    }
                }
            }
        }
        PoiDto poi = new PoiDto(poiId, "selected", extractCandidateName(context, patch),
                "RESTAURANT", 0, 0, 0, 60, List.of(), "", "", "", "");
        return new CandidateItem(1, poi, patch);
    }

    private void emitDrinksCandidatesAfterDiningSelection(ContextPack context,
                                                          PendingAction previousPending,
                                                          CandidateItem diningItem,
                                                          Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        PlanPatch diningPatch = diningItem.planPatch();
        PlanPatch drinksPatch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, "DRINKS", "DRINKS", null, null),
                new PlanPatch.Requirements(List.of(), safeAvoid(diningPatch), drinksPreferences(diningPatch),
                        "RELAXED", null, null, false),
                true);
        String selectedDiningPoiId = selectedPoiIds(diningPatch).stream().findFirst().orElse(null);
        CandidateCardResult rawResult = excludePoiFromCandidateResult(
                candidateCardService.buildCandidateCard(draft, drinksPatch), selectedDiningPoiId);
        ActionCard rawCard = rawResult.card();
        ActionCard card = new ActionCard(rawCard.id(), "附近清吧",
                "我先记住你选的餐厅。再选一个清吧，我会把吃饭和小酌一起放进拼图。",
                rawCard.options(), rawCard.inputPlaceholder(), rawCard.allowCustomInput(), rawCard.cardKind());
        CandidateCardResult result = new CandidateCardResult(card, rawResult.candidateSet());
        Map<String, Object> slots = new LinkedHashMap<>(previousPending.collectedSlots());
        slots.put("candidateChain", DINING_THEN_DRINKS);
        slots.put("nextPhase", "DRINKS");
        slots.put("orderPreference", DINING_THEN_DRINKS);
        slots.put("selectedDiningPatch", diningPatch);
        slots.put("selectedDiningLabel", diningItem.poi().name());
        if (selectedDiningPoiId != null && !selectedDiningPoiId.isBlank()) {
            slots.put("selectedDiningPoiId", selectedDiningPoiId);
        }
        PendingAction pending = new PendingAction("SELECT_CANDIDATE",
                result.candidateSet().candidateSetId(),
                result.candidateSet().targetSegmentId(),
                List.of("choose drinks", "more options", "cancel"),
                "DINING_DRINKS_CHAIN",
                diningPatch,
                diningItem.poi().name(),
                List.of("selection"),
                slots,
                true);
        sessionStateStore.saveCandidates(draft.planId(), draft.userId(), result.candidateSet(), pending,
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Dining selected; drinks candidates recommended", Instant.now()));
        emitter.accept(new SseEvent("ACTION", 3, "candidate.chain.next: DRINKS",
                List.of(), null, null, null, null, draft.planId(), draft.intent(),
                draft.orderIntents(), "PENDING_CONFIRMATION", diningPatch, result.card()));
        emitter.accept(new SseEvent("FINISH", 4,
                "我先记住「" + diningItem.poi().name() + "」。再选一个附近清吧，我会一起排进拼图。",
                List.of(), "SUCCESS", "", "", null, draft.planId(), draft.intent(),
                draft.orderIntents(), "PENDING_CONFIRMATION", diningPatch, result.card()));
    }

    private CandidateCardResult excludePoiFromCandidateResult(CandidateCardResult result, String excludedPoiId) {
        if (result == null || excludedPoiId == null || excludedPoiId.isBlank()) return result;
        if (result.candidateSet() == null || result.candidateSet().items().isEmpty()) return result;
        List<CandidateItem> items = new ArrayList<>();
        for (CandidateItem item : result.candidateSet().items()) {
            if (item.poi() == null || excludedPoiId.equals(item.poi().poiId())) continue;
            items.add(new CandidateItem(items.size() + 1, item.poi(), item.planPatch()));
        }
        List<ActionCard.ActionOption> options = result.card().options().stream()
                .filter(option -> option.poiIds() == null || !option.poiIds().contains(excludedPoiId))
                .limit(items.size())
                .toList();
        CandidateSet candidateSet = new CandidateSet(result.candidateSet().candidateSetId(),
                result.candidateSet().type(), result.candidateSet().targetSegmentId(),
                items, result.candidateSet().createdAt());
        ActionCard card = new ActionCard(result.card().id(), result.card().title(), result.card().description(),
                options, result.card().inputPlaceholder(), result.card().allowCustomInput(), result.card().cardKind());
        return new CandidateCardResult(card, candidateSet);
    }

    private void completeDiningDrinksCandidateChain(ContextPack context,
                                                    PendingAction previousPending,
                                                    CandidateItem drinksItem,
                                                    Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        Map<String, Object> slots = new LinkedHashMap<>(previousPending.collectedSlots());
        slots.put("candidateChain", DINING_THEN_DRINKS);
        slots.put("orderPreference", DINING_THEN_DRINKS);
        slots.put("selectedDrinksPatch", drinksItem.planPatch());
        slots.put("selectedDrinksLabel", drinksItem.poi().name());
        selectedPoiIds(drinksItem.planPatch()).stream().findFirst()
                .ifPresent(poiId -> slots.put("selectedDrinksPoiId", poiId));
        PendingAction chainPending = new PendingAction("PLAN_SLOT_FILLING",
                null,
                null,
                List.of("build plan"),
                "DINING_LOCKED_PLAN",
                previousPending.selectedPatch(),
                previousPending.selectedLabel(),
                List.of(),
                slots,
                true);
        emitter.accept(new SseEvent("ACTION", 3, "candidate.chain.complete: build timeline",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                drinksItem.planPatch(), null));
        PlanResponse response = planEditorEngine.applySelectedCandidateChain(draft, chainPending);
        emitPendingPlanResponse(context, chainPending, response, emitter);
    }

    private void emitPendingPlanResponse(ContextPack context,
                                         PendingAction pending,
                                         PlanResponse response,
                                         Consumer<SseEvent> emitter) {
        if (response.conflicts().isEmpty() && response.timeline() != null && !response.timeline().isEmpty()) {
            sessionStateStore.clearPending(context.planId(),
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow completed: " + pending.type(), Instant.now()));
        } else {
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow kept after validation", Instant.now()));
        }
        emitter.accept(new SseEvent("OBSERVATION", 2, "constraint.validate: conflicts=" + response.conflicts().size(),
                response.timeline(), response.status(), response.orderGroupId(), response.notificationText(),
                response.degradationNote(), response.planId(), response.intent(), response.orderIntents(),
                response.executionStatus(), pending.selectedPatch(), null));
        emitter.accept(new SseEvent("PLAN_STEP", 3, "timeline.update: pending workflow resumed",
                response.timeline(), response.status(), response.orderGroupId(), response.notificationText(),
                response.degradationNote(), response.planId(), response.intent(), response.orderIntents(),
                response.executionStatus(), pending.selectedPatch(), null));
        emitter.accept(new SseEvent("FINISH", 4, response.summary(), response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                pending.selectedPatch(), null, null, response.conflicts(), response.repairOptions(),
                response.version(), response.planStatus(), response.weather(), response.summary(), List.of()));
    }

    private List<String> drinksPreferences(PlanPatch patch) {
        List<String> prefer = new ArrayList<>();
        if (patch != null && patch.requirements() != null) {
            for (String value : patch.requirements().prefer()) {
                if (value == null || value.startsWith(runtime.getSelectedPoiPrefix())
                        || value.startsWith("MOVIE_") || "CONTEXT_READY".equals(value)) {
                    continue;
                }
                if (!prefer.contains(value)) {
                    prefer.add(value);
                }
            }
        }
        for (String value : List.of("NEARBY", "quiet_bar", "bar", "drinks")) {
            if (!prefer.contains(value)) {
                prefer.add(value);
            }
        }
        return List.copyOf(prefer);
    }

    private List<String> safeAvoid(PlanPatch patch) {
        return patch == null || patch.requirements() == null || patch.requirements().avoid() == null
                ? List.of()
                : patch.requirements().avoid();
    }

    private boolean isDiningPatch(PlanPatch patch) {
        if (patch == null) return false;
        String phase = patch.target() == null ? "" : firstNonBlank(patch.target().phase(), patch.target().activityType());
        if ("DINING".equalsIgnoreCase(phase) || "RESTAURANT".equalsIgnoreCase(phase)) {
            return true;
        }
        return selectedPoiIds(patch).stream().anyMatch(poiId ->
                replacementSearchEngine != null && replacementSearchEngine.isRestaurant(poiId));
    }

    private boolean isDrinksPatch(PlanPatch patch) {
        if (patch == null) return false;
        String phase = patch.target() == null ? "" : firstNonBlank(patch.target().phase(), patch.target().activityType());
        return "DRINKS".equalsIgnoreCase(phase) || "BAR".equalsIgnoreCase(phase);
    }

    private List<String> selectedPoiIds(PlanPatch patch) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return List.of();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(runtime.getSelectedPoiPrefix()))
                .map(value -> value.substring(runtime.getSelectedPoiPrefix().length()).trim())
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String extractCandidateName(ContextPack context, PlanPatch patch) {
        Optional<String> selectedPoiIdOpt = selectedPoiHint(patch)
                .map(val -> val.substring(runtime.getSelectedPoiPrefix().length()));
        if (context != null && selectedPoiIdOpt.isPresent()) {
            String selectedPoiId = selectedPoiIdOpt.get();
            for (CandidateSet set : context.activeCandidates()) {
                for (CandidateItem item : set.items()) {
                    if (item.poi() != null && item.poi().poiId().equals(selectedPoiId)) {
                        return item.poi().name();
                    }
                }
            }
        }
        return "candidate";
    }

    private Optional<String> selectedPoiHint(PlanPatch patch) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) return Optional.empty();
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(runtime.getSelectedPoiPrefix()))
                .findFirst();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private PlanExecutionStore.DraftPlan getDraft(ContextPack context) {
        return executionStore.find(context.planId())
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + context.planId()));
    }
}
