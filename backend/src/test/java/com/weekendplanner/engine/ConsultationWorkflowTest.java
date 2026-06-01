package com.weekendplanner.engine;



import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.planning.PlanningAssumptionService;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.workflow.ConsultationWorkflow;
import com.weekendplanner.engine.workflow.ContextualResearchPlanner;
import com.weekendplanner.engine.routing.InitialRouteCommand;
import com.weekendplanner.engine.routing.InitialRouteMode;
import com.weekendplanner.engine.routing.IntentEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.SseEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultationWorkflowTest {

    @Test
    void oneOptionTriggersRepairAndUsesRepairedChoiceBar() {
        ChatModel chatModel = chatModel(
                consultJson("只有一个方向，应该修复。", option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure")),
                consultJson("这次给你两个方向。",
                        option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure"),
                        option("有话题但不尴尬", "展览电影，有自然话题", "PREFERENCE:topic_safe"))
        );
        ConsultationWorkflow workflow = workflow(chatModel);

        ActionCard card = start(workflow).actionCard();

        assertThat(card.options()).hasSize(2);
        assertThat(card.options()).extracting(ActionCard.ActionOption::label)
                .containsExactly("轻松低压力", "有话题但不尴尬");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void twoValidOptionsAreAcceptedWithoutRepair() {
        ChatModel chatModel = chatModel(consultJson("先选一个方向。",
                option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure"),
                option("预算友好", "少排队少花钱", "PREFERENCE:budget_friendly")));
        ConsultationWorkflow workflow = workflow(chatModel);

        ActionCard card = start(workflow).actionCard();

        assertThat(card.options()).hasSize(2);
        assertThat(card.options()).allSatisfy(option -> assertThat(option.actionType()).isEqualTo("SELECT_PREFERENCE"));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void fourValidOptionsAreRenderedAsPreferenceOptions() {
        ChatModel chatModel = chatModel(consultJson("先按互动强度选。",
                option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure"),
                option("有话题但不尴尬", "展览电影，有自然话题", "PREFERENCE:topic_safe"),
                option("有一点仪式感", "晚餐夜景，氛围明显", "PREFERENCE:ritual"),
                option("下雨也合适", "室内优先，少走路", "PREFERENCE:weather_safe")));
        ConsultationWorkflow workflow = workflow(chatModel);

        ActionCard card = start(workflow).actionCard();

        assertThat(card.options()).hasSize(4);
        assertThat(card.options()).allSatisfy(option -> {
            assertThat(option.prompt()).startsWith("PREFERENCE:");
            assertThat(option.poiPreview()).isNull();
            assertThat(option.planPatch()).isNull();
            assertThat(option.poiIds()).isEmpty();
        });
    }

    @Test
    void poiLikeOptionsAreRejectedAndFallbackIsUsedAfterInvalidRepair() {
        ChatModel chatModel = chatModel(
                consultJson("这里混入了商家。",
                        option("城市艺术展览中心", "地址在商场里，0.8km", "PREFERENCE:art_center"),
                        option("某某餐厅", "评分高，适合约会", "PREFERENCE:restaurant")),
                consultJson("还是不合法。", option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure"))
        );
        ConsultationWorkflow workflow = workflow(chatModel);

        ActionCard card = start(workflow).actionCard();

        assertThat(card.options()).hasSize(5);
        assertThat(card.options()).extracting(ActionCard.ActionOption::label)
                .contains("轻松低压力", "有话题但不尴尬", "有一点仪式感", "预算友好", "下雨也合适");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void duplicateOptionsAreDedupedAndFallbackIsUsedWhenTooFewRemain() {
        ChatModel chatModel = chatModel(
                consultJson("重复项应该无效。",
                        option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure"),
                        option("轻松低压力", "咖啡散步，方便聊天", "PREFERENCE:relaxed_low_pressure")),
                consultJson("仍然无效。", option("预算友好", "少排队少花钱", "PREFERENCE:budget_friendly"))
        );
        ConsultationWorkflow workflow = workflow(chatModel);

        ActionCard card = start(workflow).actionCard();

        assertThat(card.options()).hasSize(5);
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private ConsultationWorkflow workflow(ChatModel chatModel) {
        ObjectMapper objectMapper = new ObjectMapper();
        PlanExecutionStore store = new PlanExecutionStore();
        return new ConsultationWorkflow(chatModel, new IntentExtractor((ChatModel) null, objectMapper),
                store, new SessionStateStore(), objectMapper, null, new ContextualResearchPlanner(),
                new PlanningAssumptionService(), new AgentRuntimeProperties());
    }

    private SseEvent start(ConsultationWorkflow workflow) {
        List<SseEvent> events = new ArrayList<>();
        PlanResponse ignored = workflow.start(new PlanRequest("U300", "第一次约会什么项目比较好"),
                new InitialRouteCommand(InitialRouteMode.CONSULT_CHAT, 0.9, "IDEA",
                        new IntentEvidence(false, true, false, true, false, false, false, null), null),
                events::add);
        assertThat(ignored.timeline()).isEmpty();
        return events.stream()
                .filter(event -> "FINISH".equals(event.type()))
                .findFirst()
                .orElseThrow();
    }

    private ChatModel chatModel(String... responses) {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse[] chatResponses = new ChatResponse[responses.length];
        for (int i = 0; i < responses.length; i++) {
            chatResponses[i] = response(responses[i]);
        }
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponses[0], tail(chatResponses));
        return chatModel;
    }

    private ChatResponse[] tail(ChatResponse[] responses) {
        if (responses.length <= 1) return new ChatResponse[0];
        ChatResponse[] tail = new ChatResponse[responses.length - 1];
        System.arraycopy(responses, 1, tail, 0, tail.length);
        return tail;
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private String consultJson(String message, String... options) {
        return """
                {
                  "message": "%s",
                  "choiceBar": {
                    "title": "偏好选择",
                    "description": "选择一个方向，我再继续帮你收窄。",
                    "options": [%s]
                  }
                }
                """.formatted(message, String.join(",", options));
    }

    private String option(String label, String description, String prompt) {
        return """
                {"label":"%s","description":"%s","prompt":"%s"}
                """.formatted(label, description, prompt);
    }
}
