package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.understanding.DomainIntent;
import com.weekendplanner.engine.understanding.FallbackSlotExtractor;
import com.weekendplanner.engine.understanding.LlmTurnUnderstandingExtractor;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.SlotNormalizer;
import com.weekendplanner.engine.understanding.SlotProvenance;
import com.weekendplanner.engine.understanding.SlotValue;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import com.weekendplanner.engine.understanding.UnderstandingPromptFactory;
import com.weekendplanner.engine.understanding.UnderstandingRequest;
import com.weekendplanner.engine.understanding.UnderstandingValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnUnderstandingServiceTest {

    @Test
    void llmJsonIsNormalizedIntoTypedSlots() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"FILL_PENDING_SLOTS",
                  "domainIntent":"DINING_LOCKED_PLAN",
                  "slots":[
                    {"name":"START_TIME","value":"10:00","provenance":"EXPLICIT","confidence":0.98},
                    {"name":"DURATION_RANGE","minMinutes":180,"maxMinutes":240,"provenance":"EXPLICIT","confidence":0.97},
                    {"name":"ORDER_PREFERENCE","value":"ACTIVITY_THEN_DINING","provenance":"EXPLICIT","confidence":0.96}
                  ],
                  "missingSlots":[],
                  "readOnlyQuestion":false,
                  "confidence":0.94,
                  "reasonCode":"llm.slot_fill"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "start at ten for three to four hours, activity then dining",
                null, List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.FILL_PENDING_SLOTS);
        assertThat(understanding.domainIntent()).isEqualTo(DomainIntent.DINING_LOCKED_PLAN);
        assertThat(understanding.slot(SlotName.START_TIME)).get()
                .extracting(slot -> slot.provenance()).isEqualTo(SlotProvenance.EXPLICIT);
        assertThat(service.toPendingSlots(understanding))
                .containsEntry("startTime", "10:00")
                .containsEntry("durationMinutes", 240)
                .containsEntry("maxEndTime", "14:00")
                .containsEntry("orderPreference", "ACTIVITY_THEN_DINING");
    }

    @Test
    void invalidLlmJsonFallsBackToCentralRules() {
        TurnUnderstandingService service = serviceWith("not json");

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "afternoon nearby", null, List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.FILL_PENDING_SLOTS);
        assertThat(service.toPendingSlots(understanding))
                .containsEntry("timeRange", "AFTERNOON")
                .containsEntry("locationScope", "NEARBY");
    }

    @Test
    void lowConfidenceSlotsAreRejectedByValidator() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"FILL_PENDING_SLOTS",
                  "domainIntent":"GENERIC_PLAN",
                  "slots":[
                    {"name":"HEADCOUNT","value":4,"provenance":"EXPLICIT","confidence":0.20}
                  ],
                  "missingSlots":[],
                  "readOnlyQuestion":false,
                  "confidence":0.90,
                  "reasonCode":"llm.low_confidence"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "maybe several people", null, List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.FILL_PENDING_SLOTS);
        assertThat(understanding.slot(SlotName.HEADCOUNT)).isEmpty();
        assertThat(service.toPendingSlots(understanding)).isEmpty();
    }

    @Test
    void candidateRefinementJsonKeepsSearchSlots() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"REFINE_CANDIDATES",
                  "domainIntent":"DINING",
                  "routeTarget":"WORKFLOW",
                  "slots":[
                    {"name":"SEARCH_TAG","value":"hotpot","provenance":"EXPLICIT","confidence":0.98},
                    {"name":"SEARCH_CATEGORY","value":"RESTAURANT","provenance":"EXPLICIT","confidence":0.96}
                  ],
                  "missingSlots":[],
                  "readOnlyQuestion":false,
                  "confidence":0.94,
                  "reasonCode":"llm.refine_candidates"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "any hotpot?", candidatePending(), List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.REFINE_CANDIDATES);
        assertThat(understanding.slot(SlotName.SEARCH_TAG)).get()
                .extracting(SlotValue::value).isEqualTo("hotpot");
        assertThat(service.toPendingSlots(understanding))
                .containsEntry("searchTag", "hotpot")
                .containsEntry("searchCategory", "RESTAURANT");
    }

    @Test
    void concisePendingSlotAnswerOverridesLlmReadOnlyMisroute() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"READ_ONLY_QUESTION",
                  "domainIntent":"MOVIE",
                  "slots":[],
                  "missingSlots":[],
                  "readOnlyQuestion":true,
                  "confidence":0.90,
                  "reasonCode":"llm.read_only"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "一个", moviePending(), List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.FILL_PENDING_SLOTS);
        assertThat(service.toPendingSlots(understanding))
                .containsEntry("headcount", 1)
                .containsEntry("explicit:headcount", true);
    }

    @Test
    void pendingSlotAnswerMergesFallbackHeadcountWhenLlmOnlyExtractsOtherSlots() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"FILL_PENDING_SLOTS",
                  "domainIntent":"MOVIE",
                  "slots":[
                    {"name":"START_TIME","value":"15:00","provenance":"EXPLICIT","confidence":0.96},
                    {"name":"LOCATION_SCOPE","value":"NEARBY","provenance":"EXPLICIT","confidence":0.94}
                  ],
                  "missingSlots":[],
                  "readOnlyQuestion":false,
                  "confidence":0.92,
                  "reasonCode":"llm.partial_slot_fill"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "我计划在 15:00 到 16:00 出行，总共 1 个人，就近安排",
                moviePending(), List.of(), List.of(), List.of(), "chat"));

        assertThat(service.toPendingSlots(understanding))
                .containsEntry("startTime", "15:00")
                .containsEntry("locationScope", "NEARBY")
                .containsEntry("headcount", 1)
                .containsEntry("explicit:headcount", true);
    }

    @Test
    void pendingQuestionWithHeadcountCueStaysReadOnlyQuestion() {
        TurnUnderstandingService service = serviceWith("""
                {
                  "turnIntent":"READ_ONLY_QUESTION",
                  "domainIntent":"MOVIE",
                  "slots":[],
                  "missingSlots":[],
                  "readOnlyQuestion":true,
                  "confidence":0.90,
                  "reasonCode":"llm.read_only"
                }
                """);

        TurnUnderstanding understanding = service.understand(new UnderstandingRequest(
                "一个人看这个电影合适吗", moviePending(), List.of(), List.of(), List.of(), "chat"));

        assertThat(understanding.turnIntent()).isEqualTo(TurnIntent.READ_ONLY_QUESTION);
        assertThat(service.toPendingSlots(understanding)).isEmpty();
    }

    private TurnUnderstandingService serviceWith(String response) {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotNormalizer normalizer = new SlotNormalizer();
        return new TurnUnderstandingService(
                new LlmTurnUnderstandingExtractor(chatModel(response), objectMapper,
                        new UnderstandingPromptFactory(objectMapper), normalizer),
                new FallbackSlotExtractor(),
                normalizer,
                new UnderstandingValidator());
    }

    private ChatModel chatModel(String response) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
        return chatModel;
    }

    private PendingAction moviePending() {
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(null, null, "MOVIE", "MOVIE"),
                new PlanPatch.Requirements(List.of(), List.of(),
                        List.of("MOVIE_TITLE:星际穿越", "MOVIE_ID:M001"), null, null, null, false),
                false);
        return new PendingAction("MOVIE_SCHEDULING", null, null, List.of("time", "location", "headcount"),
                "MOVIE", patch, "星际穿越", List.of("timeWindow", "locationScope", "headcount"),
                Map.of(), true);
    }

    private PendingAction candidatePending() {
        return new PendingAction("SELECT_CANDIDATE", "candidates-1", "seg-1",
                List.of("choose index", "more options", "cancel"),
                "REPLACEMENT_SEARCH", null, null, List.of("selection"), Map.of(), true);
    }
}
