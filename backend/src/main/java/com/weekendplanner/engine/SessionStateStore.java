package com.weekendplanner.engine;

import com.weekendplanner.dto.ConstraintSet;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionStateStore {

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AgentRuntimeProperties runtime;

    public SessionStateStore() {
        this(new AgentRuntimeProperties());
    }

    public SessionStateStore(AgentRuntimeProperties runtime) {
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    public SessionState getOrCreate(String planId, String userId) {
        return sessions.computeIfAbsent(planId, ignored -> new SessionState(
                planId,
                planId,
                userId,
                List.of(),
                List.of(),
                null,
                ConstraintSet.fromIntent(null),
                List.of(),
                List.of(),
                Instant.now()));
    }

    public Optional<SessionState> find(String planId) {
        return Optional.ofNullable(sessions.get(planId));
    }

    public SessionState syncDraft(PlanExecutionStore.DraftPlan draft) {
        SessionState previous = getOrCreate(draft.planId(), draft.userId());
        SessionState next = new SessionState(
                previous.sessionId(),
                draft.planId(),
                draft.userId(),
                draft.timeline(),
                previous.lastCandidates(),
                previous.pendingAction(),
                ConstraintSet.fromIntent(draft.intent()),
                previous.recentEvents(),
                previous.lockedSegments(),
                Instant.now());
        sessions.put(draft.planId(), next);
        return next;
    }

    public SessionState saveCandidates(String planId,
                                       String userId,
                                       CandidateSet candidateSet,
                                       PendingAction pendingAction,
                                       RecentEvent event) {
        SessionState previous = getOrCreate(planId, userId);
        List<CandidateSet> sets = new ArrayList<>(previous.lastCandidates());
        sets.removeIf(set -> set.candidateSetId().equals(candidateSet.candidateSetId()));
        sets.add(candidateSet);
        int candidateRetention = Math.max(1, runtime.getCandidateSetRetention());
        if (sets.size() > candidateRetention) {
            sets = sets.subList(sets.size() - candidateRetention, sets.size());
        }
        List<RecentEvent> events = appendEvent(previous.recentEvents(), event);
        SessionState next = new SessionState(previous.sessionId(), previous.planId(), previous.userId(),
                previous.currentPlan(), sets, pendingAction, previous.userConstraints(), events,
                previous.lockedSegments(), Instant.now());
        sessions.put(planId, next);
        return next;
    }

    public SessionState clearPending(String planId, RecentEvent event) {
        SessionState previous = getOrCreate(planId, "");
        SessionState next = new SessionState(previous.sessionId(), previous.planId(), previous.userId(),
                previous.currentPlan(), previous.lastCandidates(), null, previous.userConstraints(),
                appendEvent(previous.recentEvents(), event), previous.lockedSegments(), Instant.now());
        sessions.put(planId, next);
        return next;
    }

    public SessionState savePending(String planId,
                                    String userId,
                                    PendingAction pendingAction,
                                    RecentEvent event) {
        SessionState previous = getOrCreate(planId, userId);
        SessionState next = new SessionState(previous.sessionId(), previous.planId(), previous.userId(),
                previous.currentPlan(), previous.lastCandidates(), pendingAction, previous.userConstraints(),
                appendEvent(previous.recentEvents(), event), previous.lockedSegments(), Instant.now());
        sessions.put(planId, next);
        return next;
    }

    public SessionState savePreference(String planId,
                                       String userId,
                                       ConstraintSet constraints,
                                       PendingAction pendingAction,
                                       RecentEvent event) {
        SessionState previous = getOrCreate(planId, userId);
        SessionState next = new SessionState(previous.sessionId(), previous.planId(), previous.userId(),
                previous.currentPlan(), previous.lastCandidates(), pendingAction,
                constraints == null ? previous.userConstraints() : constraints,
                appendEvent(previous.recentEvents(), event), previous.lockedSegments(), Instant.now());
        sessions.put(planId, next);
        return next;
    }

    private List<RecentEvent> appendEvent(List<RecentEvent> existing, RecentEvent event) {
        List<RecentEvent> events = new ArrayList<>(existing == null ? List.of() : existing);
        if (event != null) events.add(event);
        int eventRetention = Math.max(1, runtime.getRecentEventRetention());
        if (events.size() > eventRetention) {
            events = events.subList(events.size() - eventRetention, events.size());
        }
        return List.copyOf(events);
    }
}
