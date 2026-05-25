package com.weekendplanner.engine;

import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.ReActTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文账本 - Agent 运行期间的短期记忆
 *
 * 维护:
 * - 消息历史 (contextLedger)
 * - 已选 POI 列表
 * - 时间片占用跟踪
 * - 重规划次数
 * - 完整思考链
 */
public class ContextLedger {

    private static final Logger log = LoggerFactory.getLogger(ContextLedger.class);

    private final List<Message> messages = new ArrayList<>();
    private final List<ReActTrace> trace = new ArrayList<>();
    private final Map<String, TimeSlice> timeSlots = new HashMap<>();
    private final List<PoiDto> selectedPois = new ArrayList<>();

    private int replanCount = 0;
    private int currentRadiusKm;
    private boolean degraded = false;
    private String degradationNote;

    public ContextLedger(int initialRadiusKm) {
        this.currentRadiusKm = initialRadiusKm;
    }

    // ---- 消息管理 ----

    public void addSystem(String content) {
        messages.add(new SystemMessage(content));
    }

    public void addUser(String content) {
        messages.add(new UserMessage(content));
    }

    public void addAssistant(String content) {
        messages.add(new AssistantMessage(content));
    }

    public void addObservation(String observation) {
        addSystem("Observation: " + observation);
        trace.add(new ReActTrace(trace.size() + 1, "OBSERVATION", truncate(observation, 500)));
    }

    public List<Message> getMessages() {
        return List.copyOf(messages);
    }

    // ---- 思考链追踪 ----

    public void traceThought(String thought) {
        trace.add(new ReActTrace(trace.size() + 1, "THOUGHT", truncate(thought, 500)));
        log.debug("[ReAct:{}] Thought: {}", trace.size(), truncate(thought, 200));
    }

    public void traceAction(String action, String params) {
        trace.add(new ReActTrace(trace.size() + 1, "ACTION",
                "Tool: " + action + ", Params: " + truncate(params, 300)));
        log.info("[ReAct:{}] Action: {} | {}", trace.size(), action, truncate(params, 200));
    }

    public void traceFinish(String summary) {
        trace.add(new ReActTrace(trace.size() + 1, "FINISH", summary));
    }

    public List<ReActTrace> getTrace() {
        return List.copyOf(trace);
    }

    // ---- 时间片管理 ----

    public void allocateTimeSlice(String phase, String timeRange, String poiId) {
        timeSlots.put(poiId, new TimeSlice(phase, timeRange));
    }

    public boolean isTimeOverlap(String timeRange) {
        return timeSlots.values().stream()
                .anyMatch(ts -> ts.timeRange().equals(timeRange));
    }

    public int getTotalAllocatedMinutes() {
        return timeSlots.values().stream().mapToInt(ts -> {
            String[] parts = ts.timeRange().split("-");
            if (parts.length == 2) {
                return timeToMinutes(parts[1]) - timeToMinutes(parts[0]);
            }
            return 0;
        }).sum();
    }

    // ---- POI 选择管理 ----

    public void addSelectedPoi(PoiDto poi) {
        selectedPois.add(poi);
    }

    public List<PoiDto> getSelectedPois() {
        return List.copyOf(selectedPois);
    }

    // ---- 重规划计数 ----

    public int incrementReplan() {
        replanCount++;
        return replanCount;
    }

    public int getReplanCount() {
        return replanCount;
    }

    public void resetReplanCount() {
        replanCount = 0;
    }

    // ---- 半径管理(降级用) ----

    public int getCurrentRadiusKm() {
        return currentRadiusKm;
    }

    public void expandRadius(int newRadius) {
        this.currentRadiusKm = newRadius;
    }

    // ---- 降级标记 ----

    public void markDegraded(String note) {
        this.degraded = true;
        this.degradationNote = note;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public String getDegradationNote() {
        return degradationNote;
    }

    // ---- 工具方法 ----

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public record TimeSlice(String phase, String timeRange) {}
}
