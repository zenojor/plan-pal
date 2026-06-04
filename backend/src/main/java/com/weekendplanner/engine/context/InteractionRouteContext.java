package com.weekendplanner.engine.context;

public record InteractionRouteContext(
        String userTurn,
        PendingAction pendingAction,
        java.util.List<RecentEvent> recentEvents,
        boolean hasStructuredPatch,
        String source
) {
}
