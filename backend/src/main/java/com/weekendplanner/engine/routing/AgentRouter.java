package com.weekendplanner.engine.routing;


import com.weekendplanner.engine.context.SessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.engine.runtime.AgentCommand;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.runtime.RouteMode;
import com.weekendplanner.engine.search.CandidateSearchRefinement;
import com.weekendplanner.engine.search.SearchIntentMapper;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final RouterRuleBook ruleBook;
    private final SearchIntentMapper searchIntentMapper;

    @Value("${agent.router.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Autowired
    public AgentRouter(ObjectProvider<ChatModel> chatModelProvider,
                       ObjectMapper objectMapper,
                       RouterRuleBook ruleBook) {
        this(chatModelProvider.getIfAvailable(), objectMapper, ruleBook, new SearchIntentMapper());
    }

    public AgentRouter(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this(chatModelProvider.getIfAvailable(), objectMapper, new RouterRuleBook());
    }

    public AgentRouter(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, objectMapper, new RouterRuleBook());
    }

    public AgentRouter(ChatModel chatModel, ObjectMapper objectMapper, RouterRuleBook ruleBook) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.ruleBook = ruleBook == null ? new RouterRuleBook() : ruleBook;
        this.searchIntentMapper = new SearchIntentMapper();
    }

    public AgentRouter(ChatModel chatModel,
                       ObjectMapper objectMapper,
                       RouterRuleBook ruleBook,
                       SearchIntentMapper searchIntentMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.ruleBook = ruleBook == null ? new RouterRuleBook() : ruleBook;
        this.searchIntentMapper = searchIntentMapper == null ? new SearchIntentMapper() : searchIntentMapper;
    }

    public AgentCommand route(ContextPack context) {
        return route(context, null);
    }

    public AgentCommand route(ContextPack context, TurnUnderstanding understanding) {
        AgentCommand ruleCommand = routeByRules(context, understanding);
        if (!"APPLY_FEEDBACK_PATCH".equals(ruleCommand.command()) || !llmEnabled || chatModel == null) {
            return ruleCommand;
        }
        return routeByLlm(context).orElse(ruleCommand);
    }

    private AgentCommand routeByRules(ContextPack context, TurnUnderstanding understanding) {
        String input = context.userTurn() == null ? "" : context.userTurn();
        PendingAction pending = context.pendingAction();

        if (isPlanChoicePending(pending)) {
            Optional<Integer> index = ruleBook.selectedIndex(input);
            if (index.isPresent() || input.toUpperCase(java.util.Locale.ROOT).contains("BUILD_PLAN")) {
                return new AgentCommand("SELECT_PLAN_CHOICE", 0.98, pending.targetSegmentId(),
                        pending.candidateSetId(), index.orElse(null), Map.of(), null,
                        "BUILD_SELECTED_PLAN_CHOICE", RouteMode.FAST_WORKFLOW, false, null, null);
            }
            if (ruleBook.isCancelRequest(input)) {
                return new AgentCommand("SMALLTALK_OR_UNKNOWN", 0.86, pending.targetSegmentId(),
                        pending.candidateSetId(), null, Map.of(), null,
                        "CANCEL_PENDING_ACTION", RouteMode.FAST_WORKFLOW, false, null, null);
            }
        }

        if (isCandidateDecisionPending(pending)) {
            Optional<Integer> index = ruleBook.selectedIndex(input);
            if (index.isPresent() && "SELECT_CANDIDATE".equalsIgnoreCase(pending.type())) {
                return new AgentCommand("SELECT_CANDIDATE", 0.98, pending.targetSegmentId(),
                        pending.candidateSetId(), index.get(), Map.of(), null,
                        "APPLY_CANDIDATE_TO_PLAN", RouteMode.FAST_WORKFLOW, false, null, null);
            }
            Optional<CandidateSearchRefinement> refinement = searchIntentMapper.refinementFrom(context, understanding);
            if (refinement.isPresent()) {
                return new AgentCommand("REFINE_CANDIDATES", 0.94, pending.targetSegmentId(),
                        pending.candidateSetId(), null, searchIntentMapper.toCommandSlots(refinement.get()), null,
                        "REPLACE_SEGMENT_WITH_CANDIDATES", RouteMode.FAST_WORKFLOW, false, null, null);
            }
            if (ruleBook.isReplacementRequest(input)) {
                return new AgentCommand("REPLACE_SEGMENT", 0.92, pending.targetSegmentId(),
                        pending.candidateSetId(), null, ruleBook.replacementSlots(input), null,
                        "REPLACE_SEGMENT_WITH_CANDIDATES", RouteMode.FAST_WORKFLOW, false, null, null);
            }
            if (ruleBook.isCancelRequest(input)) {
                return new AgentCommand("SMALLTALK_OR_UNKNOWN", 0.86, pending.targetSegmentId(),
                        pending.candidateSetId(), null, Map.of(), null,
                        "CANCEL_PENDING_ACTION", RouteMode.FAST_WORKFLOW, false, null, null);
            }
        }

        Optional<String> newEndTime = ruleBook.parseEndTime(input);
        if (newEndTime.isPresent() && ruleBook.isEditEndTimeRequest(input)) {
            return new AgentCommand("EDIT_TIME", 0.95, context.selectedSegmentId(), null, null,
                    Map.of("newEndTime", newEndTime.get()), null, "EXTEND_PLAN_END_TIME",
                    RouteMode.FAST_WORKFLOW, false, null, null);
        }

        if (ruleBook.isReplacementRequest(input)) {
            return new AgentCommand("REPLACE_SEGMENT", 0.9, context.selectedSegmentId(), null, null,
                    ruleBook.replacementSlots(input), null, "REPLACE_SEGMENT_WITH_CANDIDATES",
                    RouteMode.FAST_WORKFLOW, false, null, null);
        }

        if (ruleBook.isReasoningRequest(input)) {
            return new AgentCommand("ASK_EXPLANATION", 0.82, context.selectedSegmentId(), null, null,
                    Map.of(), null, "APPLY_FEEDBACK_PATCH", RouteMode.FAST_WORKFLOW, false, null, null);
        }

        return new AgentCommand("MODIFY_PLAN", 0.7, context.selectedSegmentId(), null, null,
                Map.of(), null, "APPLY_FEEDBACK_PATCH", RouteMode.FAST_WORKFLOW, false, null, null);
    }

    private boolean isPlanChoicePending(PendingAction pending) {
        return pending != null && pending.type() != null && "PLAN_CHOICE".equalsIgnoreCase(pending.type());
    }

    private boolean isCandidateDecisionPending(PendingAction pending) {
        if (pending == null || pending.type() == null) return false;
        return switch (pending.type().toUpperCase(java.util.Locale.ROOT)) {
            case "SELECT_CANDIDATE", "REPLACE_SEGMENT", "QUEUE_REPAIR", "PRODUCT_RESEARCH", "PLAN_CHOICE" -> true;
            default -> false;
        };
    }

    private Optional<AgentCommand> routeByLlm(ContextPack context) {
        try {
            String system = """
                    You are a context-aware router for a local trip planning agent.
                    Output JSON only with fields: intent, confidence, targetSegmentId, candidateSetId,
                    selectedIndex, command, routeMode, needClarification, clarificationQuestion.
                    Use FAST_WORKFLOW for all concrete edits, vague optimization, and explanation routing.
                    """;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userInput", context.userTurn() == null ? "" : context.userTurn());
            payload.put("pendingAction", context.pendingAction());
            payload.put("lastCandidates", context.activeCandidates());
            payload.put("currentPlan", context.draft() == null ? List.of() : context.draft().timeline());
            ConstraintSet constraints = context.constraints();
            payload.put("userConstraints", constraints);
            payload.put("recentEvents", context.recentEvents());
            String user = objectMapper.writeValueAsString(payload);
            String content = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .getResult().getOutput().getText();
            JsonNode node = objectMapper.readTree(extractJson(content));
            String command = text(node, "command", "APPLY_FEEDBACK_PATCH");
            return Optional.of(new AgentCommand(
                    text(node, "intent", "MODIFY_PLAN"),
                    node.path("confidence").asDouble(0.7),
                    nullableText(node, "targetSegmentId"),
                    nullableText(node, "candidateSetId"),
                    node.has("selectedIndex") && !node.path("selectedIndex").isNull()
                            ? node.path("selectedIndex").asInt()
                            : null,
                    Map.of(),
                    null,
                    command,
                    RouteMode.FAST_WORKFLOW,
                    node.path("needClarification").asBoolean(false),
                    nullableText(node, "clarificationQuestion"),
                    null));
        } catch (Exception e) {
            log.warn("[AgentRouter] LLM routing failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asText(fallback) : fallback;
    }

    private String nullableText(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() && !node.path(field).asText().isBlank()
                ? node.path(field).asText()
                : null;
    }
}
