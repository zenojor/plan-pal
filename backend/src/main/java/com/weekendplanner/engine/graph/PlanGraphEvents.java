package com.weekendplanner.engine.graph;

import com.weekendplanner.dto.SseEvent;

public final class PlanGraphEvents {

    private PlanGraphEvents() {
    }

    public record PlanGraphEvent(
            String node,
            String type,
            String content,
            SseEvent sseEvent
    ) {
    }

    public static PlanGraphEvent internal(String node, String type, String content) {
        return new PlanGraphEvent(node, type, content, null);
    }

    public static PlanGraphEvent sse(String node, SseEvent event) {
        return new PlanGraphEvent(node, event == null ? "" : event.type(),
                event == null ? "" : event.content(), event);
    }
}
