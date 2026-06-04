package com.weekendplanner.engine.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.routing.RouterRuleBook;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import com.weekendplanner.engine.understanding.UnderstandingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class InteractionRouter {

    private static final Logger log = LoggerFactory.getLogger(InteractionRouter.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final RouterRuleBook ruleBook;
    private final PendingSlotFiller pendingSlotFiller;
    private final TurnUnderstandingService understandingService;

    @Autowired
    public InteractionRouter(ObjectProvider<ChatModel> chatModelProvider,
                             ObjectMapper objectMapper,
                             RouterRuleBook ruleBook,
                             PendingSlotFiller pendingSlotFiller,
                             TurnUnderstandingService understandingService) {
        this(chatModelProvider.getIfAvailable(), objectMapper, ruleBook, pendingSlotFiller, understandingService);
    }

    public InteractionRouter(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, objectMapper, new RouterRuleBook(), new PendingSlotFiller(), TurnUnderstandingService.fallbackOnly());
    }

    public InteractionRouter(ChatModel chatModel, ObjectMapper objectMapper, RouterRuleBook ruleBook) {
        this(chatModel, objectMapper, ruleBook, new PendingSlotFiller(), TurnUnderstandingService.fallbackOnly());
    }

    public InteractionRouter(ChatModel chatModel,
                             ObjectMapper objectMapper,
                             RouterRuleBook ruleBook,
                             PendingSlotFiller pendingSlotFiller) {
        this(chatModel, objectMapper, ruleBook, pendingSlotFiller, TurnUnderstandingService.fallbackOnly());
    }

    public InteractionRouter(ChatModel chatModel,
                             ObjectMapper objectMapper,
                             RouterRuleBook ruleBook,
                             PendingSlotFiller pendingSlotFiller,
                             TurnUnderstandingService understandingService) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.ruleBook = ruleBook == null ? new RouterRuleBook() : ruleBook;
        this.pendingSlotFiller = pendingSlotFiller == null ? new PendingSlotFiller() : pendingSlotFiller;
        this.understandingService = understandingService == null ? TurnUnderstandingService.fallbackOnly() : understandingService;
    }

    public InteractionDecision route(AgentContext context, String source, String patchPayload) {
        String input = context == null || context.userInput() == null ? "" : context.userInput();
        PendingAction pending = context == null ? null : context.pendingAction();

        if (isActionCardSource(source) || isPreferenceToken(input)) {
            return InteractionDecision.of(InteractionCommand.CONTINUE_WORKFLOW, 1.0, "explicit UI workflow action");
        }
        if (looksLikeJsonPatch(patchPayload)) {
            return InteractionDecision.of(InteractionCommand.MODIFY_PLAN, 1.0, "structured patch payload");
        }
        if (pending != null && ruleBook.isCancelRequest(input)) {
            return InteractionDecision.of(InteractionCommand.CANCEL_PENDING, 0.96, "cancel pending workflow");
        }

        TurnUnderstanding understanding = understandingService.understand(UnderstandingRequest.fromContext(context, source));
        if (pending != null) {
            PendingSlotPatch slotPatch = pendingSlotFiller.extract(pending, understanding);
            if (slotPatch.shouldContinueWorkflow()) {
                return InteractionDecision.of(InteractionCommand.CONTINUE_WORKFLOW, 0.98, slotPatch.reason(), slotPatch);
            }
            if (slotPatch.question()) {
                return InteractionDecision.of(InteractionCommand.CONVERSATIONAL_QA, 0.95,
                        "pending workflow read-only question", slotPatch);
            }
        }

        Optional<InteractionDecision> understandingDecision = routeByUnderstanding(understanding);
        if (understandingDecision.isPresent()) {
            return understandingDecision.get();
        }

        Optional<InteractionDecision> llmDecision = routeByLlm(context, source);
        if (llmDecision.isPresent()) {
            return llmDecision.get();
        }

        if (pending != null && "ASK_CONTEXT".equalsIgnoreCase(pending.type()) && !looksLikeQuestion(input)) {
            return InteractionDecision.of(InteractionCommand.CONTINUE_WORKFLOW, 0.94, "continue context collection");
        }
        if (pending != null && "SELECT_CANDIDATE".equalsIgnoreCase(pending.type())) {
            if (ruleBook.selectedIndex(input).isPresent() || ruleBook.isReplacementRequest(input)) {
                return InteractionDecision.of(InteractionCommand.CONTINUE_WORKFLOW, 0.96, "continue candidate workflow");
            }
        }
        if (ruleBook.isEditEndTimeRequest(input) || ruleBook.isReplacementRequest(input)) {
            return InteractionDecision.of(InteractionCommand.MODIFY_PLAN, 0.92, "plan modification request");
        }
        if (looksLikeQuestion(input)) {
            return InteractionDecision.of(InteractionCommand.CONVERSATIONAL_QA, 0.9, "contextual question");
        }
        if (pending != null && ("ASK_CONTEXT".equalsIgnoreCase(pending.type())
                || "MOVIE_SCHEDULING".equalsIgnoreCase(pending.type())
                || "PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())
                || "SELECT_PREFERENCE".equalsIgnoreCase(pending.type())
                || "SELECT_CANDIDATE".equalsIgnoreCase(pending.type()))) {
            return InteractionDecision.of(InteractionCommand.CONTINUE_WORKFLOW, 0.72, "continue pending workflow");
        }
        return InteractionDecision.of(InteractionCommand.MODIFY_PLAN, 0.7, "default plan edit");
    }

    private Optional<InteractionDecision> routeByLlm(AgentContext context, String source) {
        if (chatModel == null || context == null) return Optional.empty();
        try {
            String system = """
                    You route a user turn for a planning product.
                    Active pending workflow state is authoritative unless the user cancels or clearly asks a read-only question.
                    Recent events in the conversation are provided under "recentEvents".
                    Return JSON only: {"command":"CONVERSATIONAL_QA|CONTINUE_WORKFLOW|MODIFY_PLAN|START_NEW_PLAN|CANCEL_PENDING|SMALLTALK_HELP","confidence":0.0,"reason":"short"}.
                    Use CONVERSATIONAL_QA when the user asks a question, asks for advice, asks if something is safe, asks about candidates, or asks about a candidate/movie.
                    Use CONTINUE_WORKFLOW for context answers that complete the active pending workflow, explicit selections, preference tokens, replace/cancel within the pending workflow, or button-like commands.
                    Use MODIFY_PLAN only for requests that change an existing timeline.
                    """;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userInput", context.userInput() == null ? "" : context.userInput());
            payload.put("source", source == null ? "" : source);
            payload.put("pendingAction", context.pendingAction());
            payload.put("lastCandidates", context.sessionState() == null ? List.of() : context.sessionState().lastCandidates());
            payload.put("timeline", context.draft() == null ? List.of() : context.draft().timeline());

            List<String> events = List.of();
            if (context.sessionState() != null && context.sessionState().recentEvents() != null) {
                events = context.sessionState().recentEvents().stream()
                        .map(event -> event.type() + ": " + event.summary())
                        .toList();
            }
            payload.put("recentEvents", events);

            String content = chatModel.call(new Prompt(List.of(
                            new SystemMessage(system),
                            new UserMessage(objectMapper.writeValueAsString(payload)))))
                    .getResult().getOutput().getText();
            JsonNode node = objectMapper.readTree(extractJson(content));
            InteractionCommand command = parseCommand(node.path("command").asText(""));
            return Optional.of(new InteractionDecision(command, node.path("confidence").asDouble(0.75),
                    node.path("reason").asText("llm route"), null, null, null, null, null, null, null));
        } catch (Exception e) {
            log.warn("[InteractionRouter] LLM routing failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<InteractionDecision> routeByUnderstanding(TurnUnderstanding understanding) {
        if (understanding == null || understanding.turnIntent() == TurnIntent.UNKNOWN) return Optional.empty();
        InteractionCommand command = switch (understanding.turnIntent()) {
            case READ_ONLY_QUESTION -> InteractionCommand.CONVERSATIONAL_QA;
            case CANCEL_PENDING -> InteractionCommand.CANCEL_PENDING;
            case FILL_PENDING_SLOTS, SELECT_CANDIDATE -> InteractionCommand.CONTINUE_WORKFLOW;
            case START_NEW_PLAN -> InteractionCommand.START_NEW_PLAN;
            case SMALLTALK -> InteractionCommand.SMALLTALK_HELP;
            case MODIFY_PLAN -> InteractionCommand.MODIFY_PLAN;
            case UNKNOWN -> null;
        };
        return command == null ? Optional.empty()
                : Optional.of(InteractionDecision.of(command, understanding.confidence(),
                understanding.reasonCode(), understanding));
    }

    private boolean looksLikeQuestion(String input) {
        return pendingSlotFiller.looksLikeQuestion(input);
    }

    private boolean isActionCardSource(String source) {
        if (source == null) return false;
        String normalized = source.toUpperCase(Locale.ROOT);
        return normalized.contains("ACTION-CARD") || normalized.contains("SELECT_PREFERENCE")
                || normalized.contains("SUBMIT_PATCH") || normalized.contains("BUILD_PLAN");
    }

    private boolean isPreferenceToken(String input) {
        return input != null && input.toUpperCase(Locale.ROOT).contains("PREFERENCE:");
    }

    private boolean looksLikeJsonPatch(String patchPayload) {
        if (patchPayload == null) return false;
        String trimmed = patchPayload.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private InteractionCommand parseCommand(String value) {
        try {
            return InteractionCommand.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return InteractionCommand.MODIFY_PLAN;
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
