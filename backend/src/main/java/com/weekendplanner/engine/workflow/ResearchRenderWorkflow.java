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
import com.weekendplanner.dto.ProductItem;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.mock.MockProductCatalog;
import com.weekendplanner.provider.MovieListingProvider;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.provider.ProductProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class ResearchRenderWorkflow {

    private final IntentExtractor intentExtractor;
    private final PlanExecutionStore executionStore;
    private final SessionStateStore sessionStateStore;
    private final PoiProvider poiProvider;
    private final MovieListingProvider movieListingProvider;
    private final ProductProvider productProvider;
    private final AgentRuntimeProperties runtime;
    private final FallbackSlotExtractor fallbackSlotExtractor = new FallbackSlotExtractor();

    @Autowired
    public ResearchRenderWorkflow(IntentExtractor intentExtractor,
                                  PlanExecutionStore executionStore,
                                  SessionStateStore sessionStateStore,
                                  PoiProvider poiProvider,
                                  MovieListingProvider movieListingProvider,
                                  ProductProvider productProvider,
                                  AgentRuntimeProperties runtime) {
        this.intentExtractor = intentExtractor;
        this.executionStore = executionStore;
        this.sessionStateStore = sessionStateStore;
        this.poiProvider = poiProvider;
        this.movieListingProvider = movieListingProvider;
        this.productProvider = productProvider == null ? new MockProductCatalog() : productProvider;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    public ResearchRenderWorkflow(IntentExtractor intentExtractor,
                                  PlanExecutionStore executionStore,
                                  SessionStateStore sessionStateStore,
                                  PoiProvider poiProvider,
                                  MovieListingProvider movieListingProvider,
                                  AgentRuntimeProperties runtime) {
        this(intentExtractor, executionStore, sessionStateStore, poiProvider, movieListingProvider,
                new MockProductCatalog(), runtime);
    }

    public PlanResponse execute(PlanRequest request,
                                InitialRouteCommand route,
                                Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent intent = consultingIntent(request, route);
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

        PendingAction pending = pendingForCandidateSet(result.candidateSet(), intent, request.prompt());
        if (isDiningDrinksDiscovery(result.candidateSet(), intent, request.prompt())) {
            emitter.accept(new SseEvent("ACTION", 4, "candidate.chain.start: DINING_THEN_DRINKS",
                    List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        }
        sessionStateStore.saveCandidates(planId, request.userId(), result.candidateSet(),
                pending,
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
        if ("PRODUCT".equalsIgnoreCase(type)) return "product.search";
        return "poi.search";
    }

    private CandidateCardResult buildCard(PlanExecutionStore.DraftPlan draft, String prompt, InitialRouteCommand route) {
        String type = route.researchType() == null ? "IDEA" : route.researchType();
        if (shouldStartWithDining(draft, prompt)) {
            type = "DINING";
        }
        if ("MOVIE".equalsIgnoreCase(type)) {
            return movieCard(draft, route.evidence(), Set.of());
        }
        if ("PRODUCT".equalsIgnoreCase(type)) {
            return productCard(draft, prompt, Set.of());
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

    public Optional<CandidateCardResult> refreshCard(PlanExecutionStore.DraftPlan draft,
                                                     String type,
                                                     String prompt,
                                                     Set<String> excludedIds) {
        if (draft == null) return Optional.empty();
        Set<String> excludes = excludedIds == null ? Set.of() : excludedIds;
        if ("MOVIE".equalsIgnoreCase(type) || "MOVIE_SCREENING".equalsIgnoreCase(type)) {
            return Optional.of(movieCard(draft, null, excludes));
        }
        if ("PRODUCT".equalsIgnoreCase(type) || "PRODUCT_RESEARCH".equalsIgnoreCase(type)) {
            return Optional.of(productCard(draft, prompt, excludes));
        }
        if ("DINING".equalsIgnoreCase(type)) {
            return Optional.of(poiCard(draft, "DINING", tagsFromPrompt(prompt, true),
                    "换一批餐饮候选", "我按新的条件又筛了一轮。", excludes));
        }
        if ("DRINKS".equalsIgnoreCase(type)) {
            return Optional.of(poiCard(draft, "DRINKS", tagsFromPrompt(prompt, true),
                    "换一批清吧候选", "我按新的条件又筛了一轮。", excludes));
        }
        return Optional.of(poiCard(draft, "ACTIVITY", tagsFromPrompt(prompt, false),
                "换一批推荐", "我避开刚才展示过的候选，重新排了一组更贴近描述的选项。", excludes));
    }

    private CandidateCardResult movieCard(PlanExecutionStore.DraftPlan draft, IntentEvidence evidence, Set<String> excludedIds) {
        String afterTime = evidence == null ? null : evidence.afterTime();
        List<MovieListingProvider.MovieListing> listings = movieListingProvider.searchByCinemaAndTime(null, afterTime)
                .stream()
                .toList();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (MovieListingProvider.MovieListing listing : listings) {
            Optional<PoiDto> cinemaOpt = poiProvider.findById(listing.cinemaId());
            if (cinemaOpt.isEmpty()) continue;
            PoiDto cinema = cinemaOpt.get();
            if (!listing.screenings().isEmpty()) {
                for (MovieListingProvider.Screening screening : listing.screenings()) {
                    if (isExcluded(excludedIds, "movie-" + screening.screeningId(), screening.screeningId(),
                            listing.movieId(), cinema.poiId())) {
                        continue;
                    }
                    PlanPatch patch = addPatch("ACTIVITY", List.of(
                            "SELECTED_POI:" + cinema.poiId(),
                            "SCREENING_ID:" + screening.screeningId(),
                            "MOVIE_ID:" + listing.movieId(),
                            "MOVIE_TITLE:" + listing.title(),
                            "CINEMA_ID:" + cinema.poiId(),
                            "MOVIE_TIME:" + nullToEmpty(screening.startTime()),
                            "MOVIE_SHOWTIMES:" + String.join("|", listing.showtimes()),
                            "MOVIE_DURATION:" + listing.durationMinutes(),
                            "MOVIE_FORMAT:" + screening.format()));
                    PoiPreview preview = preview(cinema, "movie-placeholder");
                    String description = String.format(Locale.ROOT,
                            "%s-%s · %s · %s · %s · %d 分钟 · 评分 %.1f · CNY %.0f · 余票 %d",
                            screening.startTime(), screening.endTime(), cinema.name(), screening.hall(),
                            screening.format(), listing.durationMinutes(), listing.rating(),
                            screening.pricePerTicket(), screening.remainingSeats());
                    ActionCard.MovieScreening dto = new ActionCard.MovieScreening(
                            screening.screeningId(), listing.movieId(), listing.title(), cinema.poiId(), cinema.name(),
                            screening.startTime(), screening.endTime(), screening.hall(), screening.format(),
                            screening.language(), screening.pricePerTicket(), screening.remainingSeats());
                    options.add(new ActionCard.ActionOption("movie-" + screening.screeningId(), listing.title(),
                            description, "SUBMIT_PATCH", null, null, patch, List.of(cinema.poiId()), preview,
                            "MOVIE_SCREENING")
                            .withScreening(dto)
                            .withDecision(movieScore(cinema, listing, screening, afterTime),
                                    movieReasons(cinema, listing, screening, afterTime),
                                    List.of(listing.genre(), screening.format(), screening.language()),
                                    movieTradeoffs(cinema, screening)));
                    items.add(new CandidateItem(index, cinema, patch));
                    index++;
                }
                continue;
            }
            String showtime = firstShowtimeAtOrAfter(listing.showtimes(), afterTime);
            PlanPatch patch = addPatch("ACTIVITY", List.of(
                    "SELECTED_POI:" + cinema.poiId(),
                    "MOVIE_ID:" + listing.movieId(),
                    "MOVIE_TITLE:" + listing.title(),
                    "MOVIE_TIME:" + nullToEmpty(showtime),
                    "MOVIE_SHOWTIMES:" + String.join("|", listing.showtimes()),
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

    private CandidateCardResult productCard(PlanExecutionStore.DraftPlan draft, String prompt, Set<String> excludedIds) {
        List<ProductItem> products = productProvider.searchProducts(prompt, productTags(prompt), runtime.getCandidateLimit());
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (ProductItem product : products) {
            if (isExcluded(excludedIds, "product-" + product.productId(), product.productId(), product.merchantPoiId())) {
                continue;
            }
            Optional<PoiDto> merchantOpt = poiProvider.findById(product.merchantPoiId());
            if (merchantOpt.isEmpty()) continue;
            PoiDto merchant = merchantOpt.get();
            PlanPatch patch = addPatch("DINING", List.of(
                    "SELECTED_POI:" + merchant.poiId(),
                    "PRODUCT_ID:" + product.productId(),
                    "PRODUCT_NAME:" + product.productName(),
                    "PRODUCT_CATEGORY:" + product.category()));
            PoiPreview preview = preview(merchant, "merchant-placeholder");
            String description = String.format(Locale.ROOT, "%s · %s · 评分 %.1f · 热度 %d · CNY %.0f。%s",
                    product.category(), product.merchantName(), product.rating(), product.trendingScore(),
                    product.priceCny(), product.reason());
            options.add(new ActionCard.ActionOption("product-" + product.productId(), product.productName(),
                    description, "SUBMIT_PATCH", null, null, patch, List.of(merchant.poiId()), preview,
                    "PRODUCT").withDecision(productScore(product, merchant), productReasons(product, merchant),
                    product.tags(), productTradeoffs(product, merchant)));
            items.add(new CandidateItem(index, merchant, patch));
            index++;
        }
        return result(draft, "PRODUCT", null, "选择想去试的饮品",
                "我先按商品和门店给你几个候选，选中后再把对应地点放进拼图。", options, items);
    }

    private CandidateCardResult poiCard(PlanExecutionStore.DraftPlan draft,
                                        String phase,
                                        List<String> tags,
                                        String title,
                                        String description) {
        return poiCard(draft, phase, tags, title, description, Set.of());
    }

    private CandidateCardResult poiCard(PlanExecutionStore.DraftPlan draft,
                                        String phase,
                                        List<String> tags,
                                        String title,
                                        String description,
                                        Set<String> excludedIds) {
        String category = "DINING".equalsIgnoreCase(phase) || "DRINKS".equalsIgnoreCase(phase)
                ? "RESTAURANT"
                : "ACTIVITY";
        List<PoiDto> pois = poiProvider.searchByCategory(category, tags, 5).stream()
                .filter(poi -> !isExcluded(excludedIds, "idea-" + poi.poiId(), poi.poiId()))
                .limit(runtime.getCandidateLimit())
                .toList();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (PoiDto poi : pois) {
            PlanPatch patch = addPatch(phase, List.of("SELECTED_POI:" + poi.poiId()));
            options.add(new ActionCard.ActionOption("idea-" + poi.poiId(), poi.name(),
                    candidateDescription(poi), "SUBMIT_PATCH", null, null, patch,
                    List.of(poi.poiId()), preview(poi, "merchant-placeholder"), "POI")
                    .withDecision(poiScore(poi), poiReasons(poi, tags), matchedTags(poi, tags), poiTradeoffs(poi)));
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
        String cardKind = "MOVIE".equalsIgnoreCase(type)
                ? "MOVIE_SCREENING"
                : "PRODUCT".equalsIgnoreCase(type) ? "PRODUCT_RESEARCH" : "POI";
        ActionCard card = new ActionCard("research-" + type.toLowerCase(Locale.ROOT) + "-" + draft.planId(),
                title, description, options, null, false, cardKind);
        return new CandidateCardResult(card, set);
    }

    private PendingAction pendingForCandidateSet(CandidateSet candidateSet, PlanIntent intent, String prompt) {
        String type = candidateSet == null ? "" : candidateSet.type();
        String workflowType = "MOVIE".equalsIgnoreCase(type)
                ? "MOVIE"
                : "DINING".equalsIgnoreCase(type) || "PRODUCT".equalsIgnoreCase(type)
                ? "DINING_LOCKED_PLAN" : "CONTEXTUAL_RESEARCH";
        Map<String, Object> slots = baseSlotsFromIntent(intent);
        if (isDiningDrinksDiscovery(candidateSet, intent, prompt)) {
            slots = new java.util.LinkedHashMap<>(slots);
            slots.put("candidateChain", "DINING_THEN_DRINKS");
            slots.put("nextPhase", "DRINKS");
            slots.put("orderPreference", "DINING_THEN_DRINKS");
            slots.put("originalPrompt", prompt == null ? "" : prompt);
            slots = Map.copyOf(slots);
        }
        return new PendingAction("SELECT_CANDIDATE", candidateSet.candidateSetId(), candidateSet.targetSegmentId(),
                List.of("choose index", "more options", "cancel"), workflowType, null, null,
                List.of("selection"), slots, true);
    }

    private boolean isDiningDrinksDiscovery(CandidateSet candidateSet, PlanIntent intent, String prompt) {
        if (candidateSet == null || !"DINING".equalsIgnoreCase(candidateSet.type())) return false;
        if (intent == null || intent.requestedSegments() == null) return false;
        return intent.requestedSegments().contains("DINING")
                && intent.requestedSegments().contains("DRINKS")
                && looksLikeCandidateDiscovery(prompt);
    }

    private boolean shouldStartWithDining(PlanExecutionStore.DraftPlan draft, String prompt) {
        PlanIntent intent = draft == null ? null : draft.intent();
        if (intent == null || intent.requestedSegments() == null) return false;
        return intent.requestedSegments().contains("DINING")
                && intent.requestedSegments().contains("DRINKS")
                && looksLikeCandidateDiscovery(prompt);
    }

    private boolean looksLikeCandidateDiscovery(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (text.isBlank()) return false;
        boolean explores = containsAny(text, "看看有什么", "看有什么", "有什么", "有没有", "推荐几个",
                "找找", "附近有什么", "what's nearby", "any good", "recommend");
        boolean explicitPlan = containsAny(text, "安排路线", "规划路线", "做路线", "生成方案", "完整行程",
                "做个行程", "帮我安排", "帮我规划", "itinerary", "schedule");
        return explores && !explicitPlan;
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private PlanIntent consultingIntent(PlanRequest request, InitialRouteCommand route) {
        com.weekendplanner.engine.understanding.TurnUnderstanding routeUnderstanding = route == null ? null : route.understanding();
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt(), routeUnderstanding);
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

    private boolean isExcluded(Set<String> excludedIds, String... values) {
        if (excludedIds == null || excludedIds.isEmpty()) return false;
        for (String value : values) {
            if (value != null && excludedIds.contains(value)) return true;
        }
        return false;
    }

    private double movieScore(PoiDto cinema,
                              MovieListingProvider.MovieListing listing,
                              MovieListingProvider.Screening screening,
                              String afterTime) {
        double score = 100 - cinema.distanceKm() * 9 + listing.rating() * 6 + Math.min(20, screening.remainingSeats() * 0.25);
        int after = toMinutes(afterTime);
        if (after > 0) {
            score -= Math.abs(toMinutes(screening.startTime()) - after) * 0.03;
        }
        if (screening.format().toLowerCase(Locale.ROOT).contains("imax")
                || screening.format().toLowerCase(Locale.ROOT).contains("dolby")
                || screening.format().contains("杜比")) {
            score += 8;
        }
        return Math.round(score * 10.0) / 10.0;
    }

    private List<String> movieReasons(PoiDto cinema,
                                      MovieListingProvider.MovieListing listing,
                                      MovieListingProvider.Screening screening,
                                      String afterTime) {
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format(Locale.ROOT, "%.1fkm 可达", cinema.distanceKm()));
        reasons.add("评分 " + String.format(Locale.ROOT, "%.1f", listing.rating()));
        if (afterTime != null && !afterTime.isBlank()) {
            reasons.add("接近 " + afterTime + " 后场次");
        }
        reasons.add("余票 " + screening.remainingSeats());
        return reasons;
    }

    private List<String> movieTradeoffs(PoiDto cinema, MovieListingProvider.Screening screening) {
        List<String> tradeoffs = new ArrayList<>();
        if (cinema.distanceKm() > 1.5) tradeoffs.add("距离略远");
        if (screening.remainingSeats() < 25) tradeoffs.add("余票偏少");
        if (toMinutes(screening.startTime()) >= 21 * 60) tradeoffs.add("结束较晚");
        return tradeoffs;
    }

    private double productScore(ProductItem product, PoiDto merchant) {
        double score = product.trendingScore() + product.rating() * 10 - merchant.distanceKm() * 8;
        return Math.round(score * 10.0) / 10.0;
    }

    private List<String> productReasons(ProductItem product, PoiDto merchant) {
        return List.of(
                "热度 " + product.trendingScore(),
                "评分 " + String.format(Locale.ROOT, "%.1f", product.rating()),
                String.format(Locale.ROOT, "%.1fkm 门店", merchant.distanceKm()));
    }

    private List<String> productTradeoffs(ProductItem product, PoiDto merchant) {
        List<String> tradeoffs = new ArrayList<>();
        if (merchant.distanceKm() > 1.5) tradeoffs.add("门店稍远");
        if (product.priceCny() >= 40) tradeoffs.add("价格偏高");
        return tradeoffs;
    }

    private double poiScore(PoiDto poi) {
        double score = 100 - poi.distanceKm() * 10 - Math.max(0, poi.recommendedDurationMinutes() - 90) * 0.1;
        return Math.round(score * 10.0) / 10.0;
    }

    private List<String> poiReasons(PoiDto poi, List<String> requestedTags) {
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format(Locale.ROOT, "%.1fkm", poi.distanceKm()));
        List<String> matched = matchedTags(poi, requestedTags);
        if (!matched.isEmpty()) reasons.add("命中 " + String.join("/", matched));
        reasons.add(poi.businessHours());
        return reasons;
    }

    private List<String> poiTradeoffs(PoiDto poi) {
        List<String> tradeoffs = new ArrayList<>();
        if (poi.distanceKm() > 2.0) tradeoffs.add("距离略远");
        if (poi.recommendedDurationMinutes() > 120) tradeoffs.add("耗时较长");
        return tradeoffs;
    }

    private List<String> matchedTags(PoiDto poi, List<String> requestedTags) {
        if (requestedTags == null || requestedTags.isEmpty() || poi.tags() == null) return List.of();
        return requestedTags.stream()
                .filter(tag -> poi.tags().stream().anyMatch(poiTag ->
                        poiTag.equalsIgnoreCase(tag) || poiTag.toLowerCase(Locale.ROOT).contains(tag.toLowerCase(Locale.ROOT))))
                .distinct()
                .toList();
    }

    private List<String> tagsFromPrompt(String prompt, boolean dining) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (text.contains("低糖") || text.contains("low sugar")) tags.add("low_sugar");
        if (text.contains("安静") || text.contains("quiet")) tags.add("quiet");
        if (text.contains("室内") || text.contains("indoor")) tags.add("indoor");
        if (text.contains("甜") || text.contains("dessert")) tags.add("dessert");
        if (text.contains("咖啡") || text.contains("coffee")) tags.add("coffee");
        if (text.contains("辣") || text.contains("spicy")) tags.add("spicy");
        if (text.contains("火锅") || text.contains("hotpot")) tags.add("hotpot");
        if (tags.isEmpty()) {
            tags.addAll(dining ? List.of("social_dining", "casual", "quick_bite")
                    : List.of("indoor", "casual", "coffee", "exhibition"));
        }
        return List.copyOf(tags);
    }

    private String promptFor(String type) {
        if ("PRODUCT".equalsIgnoreCase(type)) return "我先按最近比较值得试的商品和门店给你几个候选。";
        if ("MOVIE".equalsIgnoreCase(type)) return "我先按你提到的时间筛了一轮电影场次。下面不是让你选电影院，而是直接选具体电影和场次；选中后我再把影院作为地点放进拼图。";
        if ("DINING".equalsIgnoreCase(type)) return "我找到了一些餐饮选择，你可以先选一个。";
        return "我先给你几个方向，你可以继续聊偏好。";
    }

    private String summaryFor(String type) {
        if ("PRODUCT".equalsIgnoreCase(type)) return "饮品和门店候选已准备好。";
        if ("MOVIE".equalsIgnoreCase(type)) return "电影场次已准备好，先选一场你想看的。";
        if ("DINING".equalsIgnoreCase(type)) return "餐饮选项已准备好。";
        return "建议选项已准备好。";
    }

    private List<String> productTags(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (text.contains("奶茶") || text.contains("milk tea") || text.contains("bubble tea")) tags.add("奶茶");
        if (text.contains("冰沙") || text.contains("smoothie")) tags.add("冰沙");
        if (text.contains("咖啡") || text.contains("coffee")) tags.add("咖啡");
        if (text.contains("果汁") || text.contains("juice")) tags.add("果汁");
        if (text.contains("甜") || text.contains("dessert")) tags.add("甜品");
        return tags;
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
