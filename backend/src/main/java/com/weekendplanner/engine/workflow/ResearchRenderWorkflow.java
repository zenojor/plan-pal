package com.weekendplanner.engine.workflow;



import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.engine.context.RecentEventType;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.candidate.CandidateItem;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.routing.InitialRouteCommand;
import com.weekendplanner.engine.routing.IntentEvidence;
import com.weekendplanner.engine.understanding.FallbackSlotExtractor;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.PoiPreview;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.provider.MovieListingProvider;
import com.weekendplanner.provider.PoiProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class ResearchRenderWorkflow {

    private final IntentExtractor intentExtractor;
    private final PlanExecutionStore executionStore;
    private final SessionStateStore sessionStateStore;
    private final PoiProvider poiProvider;
    private final MovieListingProvider movieListingProvider;
    private final AgentRuntimeProperties runtime;
    private final FallbackSlotExtractor fallbackSlotExtractor = new FallbackSlotExtractor();

    public ResearchRenderWorkflow(IntentExtractor intentExtractor,
                                  PlanExecutionStore executionStore,
                                  SessionStateStore sessionStateStore,
                                  PoiProvider poiProvider,
                                  MovieListingProvider movieListingProvider,
                                  AgentRuntimeProperties runtime) {
        this.intentExtractor = intentExtractor;
        this.executionStore = executionStore;
        this.sessionStateStore = sessionStateStore;
        this.poiProvider = poiProvider;
        this.movieListingProvider = movieListingProvider;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    public PlanResponse execute(PlanRequest request,
                                InitialRouteCommand route,
                                Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent intent = consultingIntent(request);
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), "");
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);

        emitter.accept(new SseEvent("START", 0, "PlanPal 正在理解你的问题，并先找可选择的候选。",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("ACTION", 1, "router.decide: initial request -> " + route.mode(),
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("OBSERVATION", 1, "router.decide result: researchType=" + route.researchType()
                + ", confidence=" + route.confidence(), List.of(), null, null, null, null,
                planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("ACTION", 2, searchToolName(route.researchType()) + ": collect candidates",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        CandidateCardResult result = buildCard(draft, request.prompt(), route);
        if (result.candidateSet().items().isEmpty()) {
            emitter.accept(new SseEvent("OBSERVATION", 2, searchToolName(route.researchType()) + " result: 0 candidates",
                    List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
            String message = "我暂时没有找到足够合适的候选。你可以补充地点、时间或偏好，我再换一组条件继续找。";
            emitter.accept(new SseEvent("FINISH", 2, message, List.of(), "SUCCESS", "", "",
                    null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
            return response(planId, request.userId(), intent, message);
        }
        int candidateCount = result.candidateSet().items().size();
        emitter.accept(new SseEvent("OBSERVATION", 2, searchToolName(route.researchType()) + " result: "
                + candidateCount + " candidates", List.of(), null, null, null, null,
                planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("ACTION", 3, "candidate.rank: score and trim candidate set",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("OBSERVATION", 3, "candidate.rank selected: " + candidateCount + " options",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        sessionStateStore.saveCandidates(planId, request.userId(), result.candidateSet(),
                pendingForCandidateSet(result.candidateSet(), intent),
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Exploration candidates: " + result.candidateSet().type(), Instant.now()));
        emitter.accept(new SseEvent("ACTION", 4, "card.render: build action card",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("OBSERVATION", 4, "card.render options: " + result.card().options().size(),
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION",
                null, result.card()));

        String content = promptFor(route.researchType());
        emitter.accept(new SseEvent("THOUGHT", 5, content, List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, result.card()));
        emitter.accept(new SseEvent("FINISH", 6, summaryFor(route.researchType()), List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, result.card()));
        return response(planId, request.userId(), intent, summaryFor(route.researchType()));
    }

    private String searchToolName(String type) {
        if ("MOVIE".equalsIgnoreCase(type)) return "movie.search";
        if ("DINING".equalsIgnoreCase(type)) return "poi.search.dining";
        return "poi.search";
    }

    private CandidateCardResult buildCard(PlanExecutionStore.DraftPlan draft, String prompt, InitialRouteCommand route) {
        String type = route.researchType() == null ? "IDEA" : route.researchType();
        if ("MOVIE".equalsIgnoreCase(type)) {
            return movieCard(draft, route.evidence());
        }
        if ("DINING".equalsIgnoreCase(type)) {
            return poiCard(draft, "DINING", List.of("social_dining", "casual"), "附近餐饮",
                    "选一个候选，准备好后我再把它放进拼图。");
        }
        List<String> tags = dateLike(prompt)
                ? List.of("quiet", "coffee", "movie", "exhibition", "dessert")
                : List.of("indoor", "casual", "coffee", "exhibition");
        return poiCard(draft, "ACTIVITY", tags, "方向建议",
                "先选一个方向，我再继续帮你收窄。");
    }

    private CandidateCardResult movieCard(PlanExecutionStore.DraftPlan draft, IntentEvidence evidence) {
        String afterTime = evidence == null ? null : evidence.afterTime();
        List<MovieListingProvider.MovieListing> listings = movieListingProvider.searchByCinemaAndTime(null, afterTime)
                .stream()
                .limit(runtime.getCandidateLimit())
                .toList();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (MovieListingProvider.MovieListing listing : listings) {
            Optional<PoiDto> cinemaOpt = poiProvider.findById(listing.cinemaId());
            if (cinemaOpt.isEmpty()) continue;
            PoiDto cinema = cinemaOpt.get();
            String showtime = firstShowtimeAtOrAfter(listing.showtimes(), afterTime);
            PlanPatch patch = addPatch("ACTIVITY", List.of(
                    "SELECTED_POI:" + cinema.poiId(),
                    "MOVIE_ID:" + listing.movieId(),
                    "MOVIE_TITLE:" + listing.title(),
                    "MOVIE_TIME:" + nullToEmpty(showtime),
                    "MOVIE_DURATION:" + listing.durationMinutes()));
            PoiPreview preview = preview(cinema, "movie-placeholder");
            String description = String.format(Locale.ROOT,
                    "%s · %s · %s · %d 分钟 · 评分 %.1f · CNY %.0f",
                    nullToEmpty(showtime), cinema.name(), listing.genre(), listing.durationMinutes(),
                    listing.rating(), listing.pricePerTicket());
            options.add(new ActionCard.ActionOption("movie-" + listing.movieId(), listing.title(),
                    description, "SUBMIT_PATCH", null, null, patch, List.of(cinema.poiId()), preview,
                    "MOVIE_SCREENING"));
            items.add(new CandidateItem(index, cinema, patch));
            index++;
        }
        return result(draft, "MOVIE", null, "选择电影场次",
                "选一场电影，我会按片长和开场时间把它放进行程拼图。", options, items);
    }

    private CandidateCardResult poiCard(PlanExecutionStore.DraftPlan draft,
                                        String phase,
                                        List<String> tags,
                                        String title,
                                        String description) {
        String category = "DINING".equalsIgnoreCase(phase) ? "RESTAURANT" : "ACTIVITY";
        List<PoiDto> pois = poiProvider.searchByCategory(category, tags, 5).stream()
                .limit(runtime.getCandidateLimit())
                .toList();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (PoiDto poi : pois) {
            PlanPatch patch = addPatch(phase, List.of("SELECTED_POI:" + poi.poiId()));
            options.add(new ActionCard.ActionOption("idea-" + poi.poiId(), poi.name(),
                    candidateDescription(poi), "SUBMIT_PATCH", null, null, patch,
                    List.of(poi.poiId()), preview(poi, "merchant-placeholder"), "POI"));
            items.add(new CandidateItem(index, poi, patch));
            index++;
        }
        return result(draft, phase, null, title, description, options, items);
    }

    private CandidateCardResult result(PlanExecutionStore.DraftPlan draft,
                                       String type,
                                       String targetSegmentId,
                                       String title,
                                       String description,
                                       List<ActionCard.ActionOption> options,
                                       List<CandidateItem> items) {
        String candidateSetId = runtime.getCandidateIdPrefix() + draft.planId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        CandidateSet set = new CandidateSet(candidateSetId, type, targetSegmentId, items, Instant.now());
        String cardKind = "MOVIE".equalsIgnoreCase(type) ? "MOVIE_SCREENING" : "POI";
        ActionCard card = new ActionCard("research-" + type.toLowerCase(Locale.ROOT) + "-" + draft.planId(),
                title, description, options, null, false, cardKind);
        return new CandidateCardResult(card, set);
    }

    private PendingAction pendingForCandidateSet(CandidateSet candidateSet, PlanIntent intent) {
        String type = candidateSet == null ? "" : candidateSet.type();
        String workflowType = "MOVIE".equalsIgnoreCase(type)
                ? "MOVIE"
                : "DINING".equalsIgnoreCase(type) ? "DINING_LOCKED_PLAN" : "CONTEXTUAL_RESEARCH";
        Map<String, Object> slots = baseSlotsFromIntent(intent);
        return new PendingAction("SELECT_CANDIDATE", candidateSet.candidateSetId(), candidateSet.targetSegmentId(),
                List.of("choose index", "more options", "cancel"), workflowType, null, null,
                List.of("selection"), slots, true);
    }

    private Map<String, Object> baseSlotsFromIntent(PlanIntent intent) {
        return fallbackSlotExtractor.explicitSlotsFromIntent(intent);
    }

    private PlanPatch addPatch(String phase, List<String> prefer) {
        return new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), dedupe(prefer), null, null, null, false),
                true);
    }

    private PlanIntent consultingIntent(PlanRequest request) {
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt());
        if (extracted == null) {
            extracted = new PlanIntent(1, List.of(), "14:00", "18:00", 240, null,
                    List.of(), List.of(), null, null, request.prompt(), true);
        }
        return new PlanIntent(extracted.headcount(), extracted.participants(), extracted.startTime(),
                extracted.endTime(), extracted.totalMinutes(), extracted.sceneType(), extracted.requestedSegments(),
                extracted.dietaryConstraints(), extracted.drinkPreference(), extracted.locationScope(),
                request.prompt(), extracted.pace(), extracted.budgetLevel(), extracted.hasChildren(),
                extracted.childAge(), extracted.preferredTransportMode(), extracted.avoid(), extracted.mustHave(),
                extracted.weatherSensitive(), true);
    }

    private PlanResponse response(String planId, String userId, PlanIntent intent, String summary) {
        return new PlanResponse(planId, userId, "SUCCESS", summary, List.of(), List.of(), "", summary,
                null, intent, List.of(), "PENDING_CONFIRMATION", 1, PlanStatus.PENDING_CONFIRMATION,
                List.of(), List.of(), null);
    }

    private PoiPreview preview(PoiDto poi, String placeholder) {
        return new PoiPreview(poi.poiId(), poi.name(), poi.category(), poi.distanceKm(), poi.tags(),
                poi.address(), poi.businessHours(), poi.telephone(), poi.source(), placeholder);
    }

    private String firstShowtimeAtOrAfter(List<String> showtimes, String afterTime) {
        if (showtimes == null || showtimes.isEmpty()) return "";
        int after = toMinutes(afterTime);
        return showtimes.stream()
                .filter(time -> after <= 0 || toMinutes(time) >= after)
                .findFirst()
                .orElse(showtimes.get(0));
    }

    private String candidateDescription(PoiDto poi) {
        return String.format(Locale.ROOT, "%.1f km, %d min, tags: %s",
                poi.distanceKm(), poi.recommendedDurationMinutes(), String.join(", ", poi.tags()));
    }

    private String promptFor(String type) {
        if ("MOVIE".equalsIgnoreCase(type)) return "我先按你提到的时间筛了一轮电影场次。下面不是让你选电影院，而是直接选具体电影和场次；选中后我再把影院作为地点放进拼图。";
        if ("DINING".equalsIgnoreCase(type)) return "我找到了一些餐饮选择，你可以先选一个。";
        return "我先给你几个方向，你可以继续聊偏好。";
    }

    private String summaryFor(String type) {
        if ("MOVIE".equalsIgnoreCase(type)) return "电影场次已准备好，先选一场你想看的。";
        if ("DINING".equalsIgnoreCase(type)) return "餐饮选项已准备好。";
        return "建议选项已准备好。";
    }

    private boolean dateLike(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        return text.contains("约会") || text.contains("date") || text.contains("第一次");
    }

    private List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private int toMinutes(String time) {
        if (time == null || time.isBlank()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
