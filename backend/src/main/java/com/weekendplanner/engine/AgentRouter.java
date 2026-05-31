package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConstraintSet;
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

    @Value("${agent.router.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Autowired
    public AgentRouter(ObjectProvider<ChatModel> chatModelProvider,
                       ObjectMapper objectMapper,
                       RouterRuleBook ruleBook) {
        this(chatModelProvider.getIfAvailable(), objectMapper, ruleBook);
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
    }

    public AgentCommand route(AgentContext context) {
        AgentCommand ruleCommand = routeByRules(context);
        if (!"APPLY_FEEDBACK_PATCH".equals(ruleCommand.command()) || !llmEnabled || chatModel == null) {
            return ruleCommand;
        }
        return routeByLlm(context).orElse(ruleCommand);
    }

    private AgentCommand routeByRules(AgentContext context) {
        String input = context.userInput() == null ? "" : context.userInput();
        PendingAction pending = context.pendingAction();

        if (pending != null && "SELECT_CANDIDATE".equalsIgnoreCase(pending.type())) {
            Optional<Integer> index = ruleBook.selectedIndex(input);
            if (index.isPresent()) {
                return new AgentCommand("SELECT_CANDIDATE", 0.98, pending.targetSegmentId(),
                        pending.candidateSetId(), index.get(), Map.of(), null,
                        "APPLY_CANDIDATE_TO_PLAN", RouteMode.FAST_WORKFLOW, false, null, null);
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
            return new AgentCommand("EDIT_TIME", 0.95, context.segmentId(), null, null,
                    Map.of("newEndTime", newEndTime.get()), null, "EXTEND_PLAN_END_TIME",
                    RouteMode.FAST_WORKFLOW, false, null, null);
        }

        if (ruleBook.isReplacementRequest(input)) {
            return new AgentCommand("REPLACE_SEGMENT", 0.9, context.segmentId(), null, null,
                    ruleBook.replacementSlots(input), null, "REPLACE_SEGMENT_WITH_CANDIDATES",
                    RouteMode.FAST_WORKFLOW, false, null, null);
        }

        if (ruleBook.isReasoningRequest(input)) {
            return new AgentCommand("ASK_EXPLANATION", 0.82, context.segmentId(), null, null,
                    Map.of(), null, "PLAN_REASONING", RouteMode.AGENT_REASONING, false, null, null);
        }

        return new AgentCommand("MODIFY_PLAN", 0.7, context.segmentId(), null, null,
                Map.of(), null, "APPLY_FEEDBACK_PATCH", RouteMode.FAST_WORKFLOW, false, null, null);
    }

    private Optional<AgentCommand> routeByLlm(AgentContext context) {
        try {
            String system = """
                    You are a context-aware router for a local trip planning agent.
                    Output JSON only with fields: intent, confidence, targetSegmentId, candidateSetId,
                    selectedIndex, command, routeMode, needClarification, clarificationQuestion.
                    Use FAST_WORKFLOW for concrete edits and AGENT_REASONING only for vague optimization or explanation.
                    """;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userInput", context.userInput() == null ? "" : context.userInput());
            payload.put("pendingAction", context.pendingAction());
            payload.put("lastCandidates", context.sessionState() == null ? List.of() : context.sessionState().lastCandidates());
            payload.put("currentPlan", context.draft() == null ? List.of() : context.draft().timeline());
            ConstraintSet constraints = context.sessionState() == null
                    ? (context.draft() == null ? null : ConstraintSet.fromIntent(context.draft().intent()))
                    : context.sessionState().userConstraints();
            payload.put("userConstraints", constraints);
            payload.put("recentEvents", context.sessionState() == null ? List.of() : context.sessionState().recentEvents());
            String user = objectMapper.writeValueAsString(payload);
            String content = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .getResult().getOutput().getContent();
            JsonNode node = objectMapper.readTree(extractJson(content));
            String command = text(node, "command", "APPLY_FEEDBACK_PATCH");
            String mode = text(node, "routeMode", "FAST_WORKFLOW");
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
                    "AGENT_REASONING".equalsIgnoreCase(mode) ? RouteMode.AGENT_REASONING : RouteMode.FAST_WORKFLOW,
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
