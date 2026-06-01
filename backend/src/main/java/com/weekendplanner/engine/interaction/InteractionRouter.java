package com.weekendplanner.engine.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.routing.RouterRuleBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class InteractionRouter {

    private static final Logger log = LoggerFactory.getLogger(InteractionRouter.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final RouterRuleBook ruleBook;

    @Autowired
    public InteractionRouter(ObjectProvider<ChatModel> chatModelProvider,
                             ObjectMapper objectMapper,
                             RouterRuleBook ruleBook) {
        this(chatModelProvider.getIfAvailable(), objectMapper, ruleBook);
    }

    public InteractionRouter(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, objectMapper, new RouterRuleBook());
    }

    public InteractionRouter(ChatModel chatModel, ObjectMapper objectMapper, RouterRuleBook ruleBook) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.ruleBook = ruleBook == null ? new RouterRuleBook() : ruleBook;
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

        // Try LLM routing first for free-form text input to get the most intelligent understanding
        Optional<InteractionDecision> llmDecision = routeByLlm(context, source);
        if (llmDecision.isPresent()) {
            return llmDecision.get();
        }

        // Fallback heuristic rules if LLM routing fails or is not configured
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
                    Pending workflow state is context, not the highest priority.
                    Recent events in the conversation are provided under "recentEvents". Use them to understand the conversational context (e.g. if the bot just answered a QA question and asked the user to specify a candidate, a candidate name/title input is a follow-up and should be routed to CONVERSATIONAL_QA).
                    Return JSON only: {"command":"CONVERSATIONAL_QA|CONTINUE_WORKFLOW|MODIFY_PLAN|START_NEW_PLAN|CANCEL_PENDING|SMALLTALK_HELP","confidence":0.0,"reason":"short"}.
                    Use CONVERSATIONAL_QA when the user asks a question, asks for advice, asks if something is safe, asks about candidates, or specifies a candidate/movie to ask details about.
                    Use CONTINUE_WORKFLOW only for explicit selections to add/apply to the plan, preference tokens, context answers to complete the timeline requirements, replace/cancel within the pending workflow, or button-like commands.
                    Use MODIFY_PLAN for requests that change the timeline.
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
                    .getResult().getOutput().getContent();
            JsonNode node = objectMapper.readTree(extractJson(content));
            InteractionCommand command = parseCommand(node.path("command").asText(""));
            return Optional.of(new InteractionDecision(command, node.path("confidence").asDouble(0.75),
                    node.path("reason").asText("llm route"), null, null, null, null, null));
        } catch (Exception e) {
            log.warn("[InteractionRouter] LLM routing failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private boolean looksLikeQuestion(String input) {
        String text = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) return false;
        if (containsAny(text, List.of(
                "是什么", "为啥", "为什么", "多久", "能不能", "可以吗", "安全吗", "注意什么", "有什么区别",
                "会不会", "适合", "哪个好", "哪一个好", "怎么选", "什么意思", "头孢", "喝酒", "太吵", "聊天"))) {
            return true;
        }
        if (text.contains("?") || text.contains("？")) return true;
        return containsAny(text, List.of(
                "是什么", "为啥", "为什么", "多久", "能不能", "可以吗", "安全吗", "注意什么", "有什么区别",
                "会不会", "适合", "哪个好", "哪一个好", "怎么选", "啥意思", "头孢", "喝酒",
                "what", "why", "how long", "can i", "safe", "which", "difference"));
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

    private boolean containsAny(String text, List<String> values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
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
