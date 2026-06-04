package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
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
        return understand(new UnderstandingRequest(prompt, null, null, null, "initial"), true);
    }

    public Map<String, Object> toPendingSlots(TurnUnderstanding understanding) {
        return slotNormalizer.toPendingSlotMap(understanding);
    }

    public Map<String, Object> explicitSlotsFromIntent(PlanIntent intent) {
        return fallbackExtractor.explicitSlotsFromIntent(intent);
    }

    public boolean looksLikeQuestion(String input) {
        TurnUnderstanding understanding = fallbackExtractor.extract(new UnderstandingRequest(input, null, null, null, "question-check"));
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
                return mergeWithFallback(validated, fallback);
            }
        } catch (Exception e) {
            log.warn("[Understanding] LLM timed out or failed: {}", e.toString());
        }
        return fallback;
    }

    private TurnUnderstanding mergeWithFallback(TurnUnderstanding llm, TurnUnderstanding fallback) {
        if (llm == null || llm.confidence() <= 0) return fallback;
        if (!llm.hasSlots() && fallback.hasSlots()
                && (llm.turnIntent() == TurnIntent.FILL_PENDING_SLOTS || llm.turnIntent() == TurnIntent.UNKNOWN)) {
            return new TurnUnderstanding(
                    fallback.turnIntent(),
                    llm.domainIntent() == DomainIntent.UNKNOWN ? fallback.domainIntent() : llm.domainIntent(),
                    fallback.slots(),
                    llm.missingSlots().isEmpty() ? fallback.missingSlots() : llm.missingSlots(),
                    llm.readOnlyQuestion(),
                    llm.selectedCandidateIndex() == null ? fallback.selectedCandidateIndex() : llm.selectedCandidateIndex(),
                    Math.max(llm.confidence(), fallback.confidence()),
                    llm.reasonCode().isBlank() ? fallback.reasonCode() : llm.reasonCode());
        }
        return llm;
    }
}
