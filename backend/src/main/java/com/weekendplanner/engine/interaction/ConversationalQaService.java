package com.weekendplanner.engine.interaction;

import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PoiPreview;
import com.weekendplanner.engine.candidate.CandidateItem;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.planning.ChoiceBarTool;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class ConversationalQaService {

    private static final Logger log = LoggerFactory.getLogger(ConversationalQaService.class);

    private final ChatModel chatModel;
    private final ChoiceBarTool choiceBarTool = new ChoiceBarTool();

    @Autowired
    public ConversationalQaService(ObjectProvider<ChatModel> chatModelProvider) {
        this(chatModelProvider.getIfAvailable());
    }

    public ConversationalQaService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public ContextualQaResponse answer(ContextualQaRequest request) {
        ActionCard card = preserveActionCard(request);
        String answer = chatModel == null ? fallbackAnswer(request) : llmAnswer(request).orElseGet(() -> fallbackAnswer(request));
        return new ContextualQaResponse(answer, card);
    }

    private Optional<String> llmAnswer(ContextualQaRequest request) {
        try {
            String system = """
                    You are PlanPal inside an active planning workflow.
                    Answer the user's question naturally using the provided context.
                    The "Recent Events" section tells you what happened recently in the conversation or workflow (e.g., if the user just expressed interest in a specific candidate/movie but the action was deferred due to missing time/headcount context). Use this to resolve index or pronoun references like "this movie" (这个电影) or "it".
                    Do not select candidates, do not modify the timeline, do not clear pending actions, and do not claim you booked anything.
                    For health, medical, legal, or safety questions, give conservative general safety guidance and suggest consulting a professional.
                    End briefly by reminding the user they can continue the previous choice if relevant.
                    """;
            String user = "User question: " + safe(request.userMessage()) + "\n"
                    + "Timeline: " + timelineSummary(request.draft()) + "\n"
                    + "Pending: " + pendingSummary(request.sessionState()) + "\n"
                    + "Recent Events: " + recentEventsSummary(request.sessionState()) + "\n"
                    + "Candidates: " + candidateSummary(request.sessionState());
            String content = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .getResult().getOutput().getText();
            return Optional.ofNullable(content).filter(value -> !value.isBlank());
        } catch (Exception e) {
            log.warn("[ConversationalQaService] LLM answer failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private String recentEventsSummary(SessionState state) {
        if (state == null || state.recentEvents() == null || state.recentEvents().isEmpty()) return "";
        List<String> events = new ArrayList<>();
        List<RecentEvent> recent = state.recentEvents();
        int start = Math.max(0, recent.size() - 3);
        for (int i = start; i < recent.size(); i++) {
            RecentEvent event = recent.get(i);
            events.add(event.type() + ": " + event.summary());
        }
        return String.join("；", events);
    }

    private String fallbackAnswer(ContextualQaRequest request) {
        String text = request == null || request.userMessage() == null ? "" : request.userMessage().toLowerCase(Locale.ROOT);
        if (isIdentityQuestion(text)) {
            return "\u6211\u662f PlanPal \u91cc\u7684\u5bf9\u8bdd\u5f0f AI \u52a9\u624b\uff0c\u4e3b\u8981\u5e2e\u4f60\u7406\u89e3\u9700\u6c42\u3001\u89e3\u91ca\u5019\u9009\u548c\u6574\u7406\u884c\u7a0b\u3002\u8fd9\u4e2a\u95ee\u9898\u6211\u4e0d\u4f1a\u63a8\u8fdb\u5f53\u524d\u89c4\u5212\u6216\u4fee\u6539\u62fc\u56fe\u3002";
        }
        if (text.contains("头孢") && text.contains("酒")) {
            return "一般不建议在使用头孢类药物期间饮酒，也不要为了行程安排去冒这个风险。头孢和酒精可能引发类似双硫仑反应，出现心慌、面红、恶心、低血压等风险；具体间隔要看药物种类、剂量和个人情况，稳妥做法是咨询医生或药师。原来的行程选择我先保留，你可以继续选候选，或者让我把酒吧换成无酒精/咖啡类安排。";
        }
        String candidates = candidateSummary(request == null ? null : request.sessionState());
        if (!candidates.isBlank()) {
            return "我先按当前候选解释一下：" + candidates + "。我不会替你自动选择或改行程；原来的候选还在，你可以继续说“第几个”、让我换一批，或者继续问我它们的区别。";
        }
        String timeline = timelineSummary(request == null ? null : request.draft());
        if (!timeline.isBlank()) {
            return "我看了一下当前行程：" + timeline + "。这个问题我可以先解释，但不会自动修改拼图；如果你想调整，可以直接说要换哪一段。";
        }
        return "这个问题我可以先回答，但不会自动推进当前流程或修改行程。你也可以继续告诉我想选哪个、换一批，或者补充新的安排要求。";
    }

    private boolean isIdentityQuestion(String text) {
        return text.contains("\u4f60\u662f\u4ec0\u4e48\u6a21\u578b")
                || text.contains("\u4f60\u662f\u54ea\u4e2a\u6a21\u578b")
                || text.contains("\u4ec0\u4e48\u6a21\u578b")
                || text.contains("\u4f60\u662f\u8c01")
                || text.contains("\u4f60\u80fd\u5e72\u4ec0\u4e48")
                || text.contains("\u4f60\u80fd\u5e72\u561b")
                || text.contains("\u4f60\u80fd\u505a\u4ec0\u4e48")
                || text.contains("what model")
                || text.contains("which model")
                || text.contains("who are you")
                || text.contains("what can you do");
    }

    private ActionCard preserveActionCard(ContextualQaRequest request) {
        SessionState state = request == null ? null : request.sessionState();
        PendingAction pending = state == null ? null : state.pendingAction();
        if (pending == null) return null;
        if ("SELECT_CANDIDATE".equalsIgnoreCase(pending.type())) {
            return candidateCard(state, pending);
        }
        if ("SELECT_PREFERENCE".equalsIgnoreCase(pending.type())) {
            return preferenceCard(state.planId());
        }
        return null;
    }

    private ActionCard candidateCard(SessionState state, PendingAction pending) {
        CandidateSet set = state.lastCandidates().stream()
                .filter(candidateSet -> candidateSet.candidateSetId().equals(pending.candidateSetId()))
                .findFirst()
                .orElse(null);
        if (set == null || set.items().isEmpty()) return null;
        boolean isMovie = "MOVIE".equalsIgnoreCase(set.type());
        List<ActionCard.ActionOption> options = new ArrayList<>();
        for (CandidateItem item : set.items()) {
            PoiPreview preview = new PoiPreview(item.poi().poiId(), item.poi().name(), item.poi().category(),
                    item.poi().distanceKm(), item.poi().tags(), item.poi().address(), item.poi().businessHours(),
                    item.poi().telephone(), item.poi().source(), isMovie ? "movie-placeholder" : "merchant-placeholder");
            String label = isMovie ? movieTitle(item.planPatch()).orElse(item.poi().name()) : item.poi().name();
            String description = isMovie ? movieDescription(item.planPatch(), item.poi().name()) : poiDescription(item);
            options.add(new ActionCard.ActionOption(
                    (isMovie ? "movie-" : "candidate-") + item.index(),
                    label,
                    description,
                    "SUBMIT_PATCH",
                    set.targetSegmentId(),
                    null,
                    item.planPatch(),
                    List.of(item.poi().poiId()),
                    preview,
                    isMovie ? "MOVIE_SCREENING" : "POI"));
        }
        return new ActionCard(
                "pending-candidates-" + set.candidateSetId(),
                isMovie ? "选择电影场次" : "继续选择候选",
                isMovie ? "原来的电影场次还在，选一场我再放进拼图。" : "原来的候选还在，选一个我再放进拼图。",
                options,
                null,
                false,
                isMovie ? "MOVIE_SCREENING" : "POI");
    }

    private ActionCard preferenceCard(String planId) {
        return choiceBarTool.renderChoiceBar(new ChoiceBarTool.ChoiceBarSpec(
                "consult-choice-" + (planId == null ? "pending" : planId),
                "偏好选择",
                "原来的偏好选择还在，你可以继续选一个方向。",
                List.of(
                        new ChoiceBarTool.ChoiceBarOption("pref-relaxed", "轻松低压力",
                                "咖啡、散步、甜品，重点是舒服好聊。", "SELECT_PREFERENCE", "PREFERENCE:relaxed_low_pressure"),
                        new ChoiceBarTool.ChoiceBarOption("pref-topic", "有话题但不尴尬",
                                "展览、电影、书店，适合自然接话。", "SELECT_PREFERENCE", "PREFERENCE:topic_safe"),
                        new ChoiceBarTool.ChoiceBarOption("pref-ritual", "有一点仪式感",
                                "晚餐、清吧、夜景，氛围更明显。", "SELECT_PREFERENCE", "PREFERENCE:ritual"),
                        new ChoiceBarTool.ChoiceBarOption("pref-budget", "预算友好",
                                "少排队、少花钱、节奏轻松。", "SELECT_PREFERENCE", "PREFERENCE:budget_friendly")),
                "也可以继续问我为什么推荐这些方向",
                true));
    }

    private String candidateSummary(SessionState state) {
        if (state == null || state.lastCandidates().isEmpty()) return "";
        CandidateSet set = state.lastCandidates().get(state.lastCandidates().size() - 1);
        List<String> names = set.items().stream()
                .limit(4)
                .map(item -> item.index() + ". " + movieTitle(item.planPatch()).orElse(item.poi().name())
                        + extraMovieInfo(item.planPatch()))
                .toList();
        return String.join("；", names);
    }

    private String timelineSummary(PlanExecutionStore.DraftPlan draft) {
        if (draft == null || draft.timeline() == null || draft.timeline().isEmpty()) return "";
        return draft.timeline().stream()
                .filter(step -> step != null && !step.isTransit())
                .limit(5)
                .map(step -> step.startTime() + " " + step.poiName() + " " + step.phase())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private String pendingSummary(SessionState state) {
        PendingAction pending = state == null ? null : state.pendingAction();
        return pending == null ? "" : pending.type();
    }

    private String movieDescription(PlanPatch patch, String cinemaName) {
        return "场次 " + selectedMetadata(patch, "MOVIE_TIME:").orElse("")
                + " · " + cinemaName
                + selectedMetadata(patch, "MOVIE_DURATION:").map(value -> " · " + value + " 分钟").orElse("");
    }

    private String poiDescription(CandidateItem item) {
        return String.format(Locale.ROOT, "%.1f km, tags: %s", item.poi().distanceKm(), String.join(", ", item.poi().tags()));
    }

    private Optional<String> movieTitle(PlanPatch patch) {
        return selectedMetadata(patch, "MOVIE_TITLE:");
    }

    private String extraMovieInfo(PlanPatch patch) {
        Optional<String> time = selectedMetadata(patch, "MOVIE_TIME:");
        Optional<String> duration = selectedMetadata(patch, "MOVIE_DURATION:");
        if (time.isEmpty() && duration.isEmpty()) return "";
        return " (" + time.orElse("") + duration.map(value -> ", " + value + "分钟").orElse("") + ")";
    }

    private Optional<String> selectedMetadata(PlanPatch patch, String prefix) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return Optional.empty();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
