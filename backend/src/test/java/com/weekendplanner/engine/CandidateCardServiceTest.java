package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.candidate.CandidateCardService;
import com.weekendplanner.engine.patch.PlanPatchFactory;
import com.weekendplanner.engine.planning.RenderTextService;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateCardServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void replaceOperationCollectsAllTimelinePoiIdsToExclude() {
        // Prepare mock dependencies
        Set<String>[] capturedUsedIds = new Set[1];
        ReplacementSearchEngine searchEngine = Mockito.mock(ReplacementSearchEngine.class);
        
        Mockito.when(searchEngine.findCandidates(
                Mockito.anyString(),
                Mockito.any(PlanPatch.class),
                Mockito.any(),
                Mockito.anySet(),
                Mockito.anyInt()
        )).thenAnswer(invocation -> {
            capturedUsedIds[0] = invocation.getArgument(3);
            // Return some dummy candidates so CandidateCardService builds options
            return List.of(new PoiDto("POI_NEW", "New Trampoline Park", "ENTERTAINMENT", 120.0, 30.0, 1.2, 120, List.of()));
        });

        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        PlanPatchFactory patchFactory = new PlanPatchFactory(properties);
        RenderTextService textService = new RenderTextService();

        CandidateCardService candidateCardService = new CandidateCardService(searchEngine, patchFactory, properties, textService);

        // Prepare a timeline with two POIs: POI_1 and POI_2
        PlanStep step1 = new PlanStep(60, "09:00", "10:00", "ACTIVITY", "Trampoline", "POI_1", "Old Trampoline Park 1", "CONFIRMED", "", new double[]{0, 0}, "family", "reason", "50", 2, "", "", "", "seg-1");
        PlanStep step2 = new PlanStep(60, "10:00", "11:00", "ACTIVITY", "Trampoline", "POI_2", "Old Trampoline Park 2", "CONFIRMED", "", new double[]{0, 0}, "family", "reason", "50", 2, "", "", "", "seg-2");

        PlanExecutionStore.DraftPlan draftPlan = new PlanExecutionStore.DraftPlan(
                "plan-123",
                "user-456",
                null,
                List.of(step1, step2),
                List.of(),
                "Notification"
        );

        // We want to REPLACE the first POI (seg-1)
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target("seg-1", null, "ACTIVITY", "ACTIVITY", null, null),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                true);

        CandidateCardResult result = candidateCardService.buildCandidateCard(draftPlan, patch);

        assertThat(result).isNotNull();
        // The usedIds set passed to the search engine must contain both POI_1 and POI_2
        assertThat(capturedUsedIds[0]).containsExactlyInAnyOrder("POI_1", "POI_2");
    }
}
