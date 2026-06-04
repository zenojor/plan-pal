package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.ContextAssembler;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.engine.context.RecentEventType;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextPackTest {

    @Test
    void contextAssemblerRejectsWrongUserForPlan() {
        PlanExecutionStore store = new PlanExecutionStore();
        store.save(new PlanExecutionStore.DraftPlan("plan-1", "U001", intent(), timeline(), List.of(), "notify"));
        ContextAssembler assembler = new ContextAssembler(store, new SessionStateStore());

        assertThatThrownBy(() -> assembler.assemblePack("plan-1", "U002", "hello", null, List.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Plan does not belong to user");
    }

    @Test
    void contextPackRetainsTransitContractAndAllowedTools() {
        PlanExecutionStore store = new PlanExecutionStore();
        store.save(new PlanExecutionStore.DraftPlan("plan-1", "U001", intent(), timeline(), List.of(), "notify"));
        ContextAssembler assembler = new ContextAssembler(store, new SessionStateStore());

        ContextPack pack = assembler.assemblePack("plan-1", "U001", "what next", "seg-1",
                List.of("searchNearby", "checkAvailability"));

        assertThat(pack.userId()).isEqualTo("U001");
        assertThat(pack.planId()).isEqualTo("plan-1");
        assertThat(pack.allowedTools()).containsExactly("searchNearby", "checkAvailability");
        assertThat(pack.draft().timeline()).anySatisfy(step -> {
            assertThat(step.phase()).isEqualTo("TRANSIT");
            assertThat(step.isTransit()).isTrue();
        });
        assertThat(pack.draft().timelineSummary()).contains("TRANSIT");
    }

    @Test
    void sessionStateStoreRetainsRecentEventsAndCandidateSets() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties();
        runtime.setRecentEventRetention(2);
        runtime.setCandidateSetRetention(1);
        SessionStateStore store = new SessionStateStore(runtime);
        PendingAction pending = new PendingAction("SELECT_CANDIDATE", "candidates-3", "seg-1", List.of());

        store.saveCandidates("plan-1", "U001", candidates("candidates-1"), pending, event("one"));
        store.saveCandidates("plan-1", "U001", candidates("candidates-2"), pending, event("two"));
        SessionState state = store.saveCandidates("plan-1", "U001", candidates("candidates-3"), pending, event("three"));

        assertThat(state.lastCandidates()).extracting(CandidateSet::candidateSetId)
                .containsExactly("candidates-3");
        assertThat(state.recentEvents()).extracting(RecentEvent::summary)
                .containsExactly("two", "three");
    }

    private PlanIntent intent() {
        return new PlanIntent(1, List.of("self"), "14:00", "16:00", 120,
                "SOLO", List.of("ACTIVITY"), List.of(), "", "nearby", "test");
    }

    private List<PlanStep> timeline() {
        return List.of(
                new PlanStep(60, "14:00", "15:00", "ACTIVITY", "Visit", "P001", "Museum",
                        "NONE", "", new double[]{121.0, 31.0}, "solo", "reason", "CNY 60",
                        1, "", "", "", "seg-1"),
                new PlanStep(15, "15:00", "15:15", "TRANSIT", "Walk", "", "",
                        "NONE", "", null, "solo", "", "CNY 0", 1, "", "", "",
                        true, "WALK", 0.8, "Museum", "Cafe"));
    }

    private CandidateSet candidates(String id) {
        return new CandidateSet(id, "REPLACE", "seg-1", List.of(), Instant.now());
    }

    private RecentEvent event(String summary) {
        return new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED, summary, Instant.now());
    }
}
