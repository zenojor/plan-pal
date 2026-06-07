package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.engine.runtime.BackendNoticeSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class TurnUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(TurnUnderstandingService.class);

    private final LlmTurnUnderstandingExtractor llmExtractor;
    private final FallbackSlotExtractor fallbackExtractor;
    private final SlotNormalizer slotNormalizer;
    private final UnderstandingValidator validator;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("turn-understanding-pool");
        return thread;
    });

    @Value("${agent.understanding.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Value("${agent.understanding.chat-timeout-ms:2500}")
    private long chatTimeoutMs = 2500;

    @Value("${agent.understanding.initial-timeout-ms:5000}")
    private long initialTimeoutMs = 5000;

    @Value("${agent.understanding.fallback-rules-enabled:true}")
    private boolean fallbackRulesEnabled = true;

    @Value("${agent.understanding.shadow-mode:false}")
    private boolean shadowMode = false;

    public TurnUnderstandingService(LlmTurnUnderstandingExtractor llmExtractor,
                                    FallbackSlotExtractor fallbackExtractor,
                                    SlotNormalizer slotNormalizer,
                                    UnderstandingValidator validator) {
        this.llmExtractor = llmExtractor;
        this.fallbackExtractor = fallbackExtractor == null ? new FallbackSlotExtractor() : fallbackExtractor;
        this.slotNormalizer = slotNormalizer == null ? new SlotNormalizer() : slotNormalizer;
        this.validator = validator == null ? new UnderstandingValidator() : validator;
    }

    public static TurnUnderstandingService fallbackOnly() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotNormalizer normalizer = new SlotNormalizer();
        FallbackSlotExtractor fallback = new FallbackSlotExtractor();
        return new TurnUnderstandingService(
                new LlmTurnUnderstandingExtractor((ChatModel) null, objectMapper, new UnderstandingPromptFactory(objectMapper), normalizer),
                fallback,
                normalizer,
                new UnderstandingValidator());
    }

    public TurnUnderstanding understand(UnderstandingRequest request) {
        return understand(request, false);
    }

    public TurnUnderstanding understandInitial(String prompt) {
        return understand(new UnderstandingRequest(prompt, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), "initial"), true);
    }

    public Map<String, Object> toPendingSlots(TurnUnderstanding understanding) {
        return slotNormalizer.toPendingSlotMap(understanding);
    }

    public Map<String, Object> explicitSlotsFromIntent(PlanIntent intent) {
        return fallbackExtractor.explicitSlotsFromIntent(intent);
    }

    public boolean looksLikeQuestion(String input) {
        TurnUnderstanding understanding = fallbackExtractor.extract(new UnderstandingRequest(input, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), "question-check"));
        return understanding.readOnlyQuestion() || understanding.turnIntent() == TurnIntent.READ_ONLY_QUESTION;
    }

    private TurnUnderstanding understand(UnderstandingRequest request, boolean initial) {
        TurnUnderstanding fallback = fallbackRulesEnabled ? validator.validate(fallbackExtractor.extract(request)) : TurnUnderstanding.empty();
        if (!llmEnabled || llmExtractor == null) return fallback;
        try {
            Optional<TurnUnderstanding> maybeLlm = CompletableFuture
                    .supplyAsync(() -> llmExtractor.extract(request), executor)
                    .get(initial ? initialTimeoutMs : chatTimeoutMs, TimeUnit.MILLISECONDS);
            if (maybeLlm.isPresent()) {
                TurnUnderstanding validated = validator.validate(maybeLlm.get());
                if (shadowMode) {
                    log.debug("[Understanding] shadow llm={} fallback={}", validated, fallback);
                    return fallback;
                }
                return mergeWithFallback(validated, fallback, request);
            }
        } catch (Exception e) {
            log.warn("[Understanding] LLM timed out or failed: {}", e.toString());
            BackendNoticeSink.warn("Understanding", "LLM timed out or failed, using fallback slots: " + e);
        }
        return fallback;
    }

    private TurnUnderstanding mergeWithFallback(TurnUnderstanding llm, TurnUnderstanding fallback, UnderstandingRequest request) {
        if (llm == null || llm.confidence() <= 0) return fallback;
        if (shouldPreferPendingFallbackSlots(llm, fallback, request)) return fallback;
        if (shouldPreferInitialTripFallback(llm, fallback, request)) return fallback;
        if (shouldPreferInitialDiscoveryFallback(llm, fallback, request)) return fallback;
        if (shouldPreferCompleteInitialPlanFallback(llm, fallback, request)) return fallback;
        if (shouldMergePendingFallbackSlots(llm, fallback, request)) {
            return mergePendingFallbackSlots(llm, fallback);
        }
        if (!llm.hasSlots() && fallback.hasSlots()
                && (llm.turnIntent() == TurnIntent.FILL_PENDING_SLOTS || llm.turnIntent() == TurnIntent.UNKNOWN)) {
            return new TurnUnderstanding(
                    fallback.turnIntent(),
                    llm.domainIntent() == DomainIntent.UNKNOWN ? fallback.domainIntent() : llm.domainIntent(),
                    llm.routeTarget() == RouteTarget.UNKNOWN ? fallback.routeTarget() : llm.routeTarget(),
                    fallback.slots(),
                    llm.missingSlots().isEmpty() ? fallback.missingSlots() : llm.missingSlots(),
                    llm.readOnlyQuestion(),
                    llm.selectedCandidateIndex() == null ? fallback.selectedCandidateIndex() : llm.selectedCandidateIndex(),
                    Math.max(llm.confidence(), fallback.confidence()),
                    llm.reasonCode().isBlank() ? fallback.reasonCode() : llm.reasonCode());
        }
        return llm;
    }

    private boolean shouldMergePendingFallbackSlots(TurnUnderstanding llm,
                                                    TurnUnderstanding fallback,
                                                    UnderstandingRequest request) {
        if (request == null || request.pendingAction() == null) return false;
        if (llm == null || fallback == null || !fallback.hasSlots()) return false;
        if (llm.turnIntent() != TurnIntent.FILL_PENDING_SLOTS && llm.turnIntent() != TurnIntent.UNKNOWN) return false;
        return llm.hasSlots();
    }

    private TurnUnderstanding mergePendingFallbackSlots(TurnUnderstanding llm, TurnUnderstanding fallback) {
        java.util.EnumMap<SlotName, SlotValue> mergedSlots = new java.util.EnumMap<>(SlotName.class);
        mergedSlots.putAll(fallback.slots());
        mergedSlots.putAll(llm.slots());
        return new TurnUnderstanding(
                llm.turnIntent() == TurnIntent.UNKNOWN ? fallback.turnIntent() : llm.turnIntent(),
                llm.domainIntent() == DomainIntent.UNKNOWN ? fallback.domainIntent() : llm.domainIntent(),
                llm.routeTarget() == RouteTarget.UNKNOWN ? fallback.routeTarget() : llm.routeTarget(),
                mergedSlots,
                llm.missingSlots().isEmpty() ? fallback.missingSlots() : llm.missingSlots(),
                llm.readOnlyQuestion(),
                llm.selectedCandidateIndex() == null ? fallback.selectedCandidateIndex() : llm.selectedCandidateIndex(),
                Math.max(llm.confidence(), fallback.confidence()),
                llm.reasonCode().isBlank() ? fallback.reasonCode() : llm.reasonCode());
    }

    private boolean shouldPreferInitialTripFallback(TurnUnderstanding llm,
                                                    TurnUnderstanding fallback,
                                                    UnderstandingRequest request) {
        if (request == null || !"initial".equalsIgnoreCase(request.source())) return false;
        if (fallback == null || fallback.turnIntent() == TurnIntent.UNKNOWN) return false;
        boolean fallbackTrip = fallback.turnIntent() == TurnIntent.TRIP_IDEA
                || fallback.turnIntent() == TurnIntent.TRIP_RESEARCH
                || fallback.turnIntent() == TurnIntent.PLAN_BUILD
                || fallback.turnIntent() == TurnIntent.ASK_CLARIFICATION;
        boolean llmNonTrip = llm == null
                || llm.turnIntent() == TurnIntent.GENERAL_QA
                || llm.turnIntent() == TurnIntent.SMALLTALK
                || llm.turnIntent() == TurnIntent.UNKNOWN
                || llm.domainIntent() == DomainIntent.NON_TRIP;
        return fallbackTrip && llmNonTrip && fallback.confidence() >= 0.74;
    }

    private boolean shouldPreferInitialDiscoveryFallback(TurnUnderstanding llm,
                                                         TurnUnderstanding fallback,
                                                         UnderstandingRequest request) {
        if (request == null || !"initial".equalsIgnoreCase(request.source())) return false;
        if (!looksLikeCandidateDiscovery(request.userTurn())) return false;
        if (fallback == null || fallback.routeTarget() != RouteTarget.RESEARCH
                || fallback.turnIntent() != TurnIntent.TRIP_RESEARCH) {
            return false;
        }
        boolean llmBuildsPlan = llm != null
                && (llm.routeTarget() == RouteTarget.PLAN
                || llm.turnIntent() == TurnIntent.PLAN_BUILD
                || llm.turnIntent() == TurnIntent.TRIP_IDEA);
        boolean llmMissedConcreteSearchType = llm != null
                && (llm.routeTarget() == RouteTarget.RESEARCH || llm.turnIntent() == TurnIntent.TRIP_RESEARCH)
                && isConcreteSearchDomain(fallback.domainIntent())
                && !isConcreteSearchDomain(llm.domainIntent());
        return (llmBuildsPlan || llmMissedConcreteSearchType) && fallback.confidence() >= 0.78;
    }

    private boolean isConcreteSearchDomain(DomainIntent domainIntent) {
        return domainIntent == DomainIntent.DINING
                || domainIntent == DomainIntent.DINING_LOCKED_PLAN
                || domainIntent == DomainIntent.PRODUCT
                || domainIntent == DomainIntent.MOVIE;
    }

    private boolean shouldPreferCompleteInitialPlanFallback(TurnUnderstanding llm,
                                                           TurnUnderstanding fallback,
                                                           UnderstandingRequest request) {
        if (request == null || !"initial".equalsIgnoreCase(request.source())) return false;
        if (looksLikeCandidateDiscovery(request.userTurn())) return false;
        if (fallback == null || fallback.turnIntent() != TurnIntent.PLAN_BUILD
                || fallback.routeTarget() != RouteTarget.PLAN) {
            return false;
        }
        boolean fallbackHasTime = fallback.slot(SlotName.START_TIME).isPresent()
                || fallback.slot(SlotName.END_TIME).isPresent()
                || fallback.slot(SlotName.MAX_END_TIME).isPresent()
                || fallback.slot(SlotName.TIME_RANGE).isPresent();
        boolean fallbackHasHeadcount = fallback.slot(SlotName.HEADCOUNT).isPresent();
        boolean fallbackHasDuration = fallback.slot(SlotName.DURATION_RANGE).isPresent();
        if (!fallbackHasTime || !fallbackHasHeadcount || !fallbackHasDuration) return false;
        boolean llmDowngradedToResearch = llm == null
                || llm.routeTarget() == RouteTarget.RESEARCH
                || llm.turnIntent() == TurnIntent.TRIP_RESEARCH
                || llm.turnIntent() == TurnIntent.TRIP_IDEA;
        return llmDowngradedToResearch && fallback.confidence() >= 0.78;
    }

    private boolean looksLikeCandidateDiscovery(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (text.isBlank()) return false;
        boolean explores = containsAny(text, "看看有什么", "看有什么", "有什么", "有没有", "推荐几个",
                "找找", "附近有什么", "what's nearby", "any good", "recommend");
        boolean explicitPlan = containsAny(text, "安排路线", "规划路线", "做路线", "生成方案", "完整行程",
                "做个行程", "帮我安排", "帮我规划", "itinerary", "schedule");
        boolean candidateTarget = containsAny(text, "吃", "餐", "饭", "好喝", "饮品", "咖啡", "奶茶", "甜品",
                "清吧", "酒吧", "bar", "电影", "影院", "商品", "附近");
        return explores && candidateTarget && !explicitPlan;
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

    private boolean shouldPreferPendingFallbackSlots(TurnUnderstanding llm,
                                                     TurnUnderstanding fallback,
                                                     UnderstandingRequest request) {
        if (request == null || request.pendingAction() == null) return false;
        if (fallback == null || !fallback.hasSlots() || fallback.turnIntent() != TurnIntent.FILL_PENDING_SLOTS) {
            return false;
        }
        if (llm == null || llm.hasSlots()) return false;
        if (llm.turnIntent() == TurnIntent.CANCEL_PENDING
                || llm.turnIntent() == TurnIntent.START_NEW_PLAN
                || llm.turnIntent() == TurnIntent.GENERAL_QA
                || llm.turnIntent() == TurnIntent.SMALLTALK) {
            return false;
        }
        return isConcisePendingSlotAnswer(request.userTurn(), fallback);
    }

    private boolean isConcisePendingSlotAnswer(String input, TurnUnderstanding fallback) {
        if ("fallback.contextual_headcount".equals(fallback.reasonCode())) return true;
        String compact = input == null ? "" : input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？]", "");
        return !compact.isBlank()
                && compact.length() <= 8
                && !containsQuestionCue(compact);
    }

    private boolean containsQuestionCue(String compact) {
        return compact.contains("吗")
                || compact.contains("什么")
                || compact.contains("为啥")
                || compact.contains("为什么")
                || compact.contains("怎么")
                || compact.contains("合适")
                || compact.contains("适合")
                || compact.contains("可以")
                || compact.contains("能不能")
                || compact.contains("how")
                || compact.contains("why")
                || compact.contains("what");
    }
}
