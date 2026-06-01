package com.weekendplanner.engine;


import com.weekendplanner.engine.patch.PlanPatchFactory;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPatchFactoryTest {

    private final PlanPatchFactory factory = new PlanPatchFactory(new AgentRuntimeProperties());

    @Test
    void selectedPoiMarkerIsCentralizedAndExtractable() {
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target("seg-1", null, "DINING", "DINING", null, null),
                new PlanPatch.Requirements(List.of(), List.of(), List.of("NEARBY"), null, null, null, false),
                true);

        PlanPatch selected = factory.withSelectedPoi(patch, "seg-1", "P001", "DINING");

        assertThat(selected.requirements().prefer()).contains("SELECTED_POI:P001");
        assertThat(factory.selectedPoiId(selected)).contains("P001");
        assertThat(selected.target().segmentId()).isEqualTo("seg-1");
    }

    @Test
    void editEndTimeProducesCompatiblePlanDelta() {
        PlanDelta delta = factory.editEndTime("14:00", "22:00");

        assertThat(delta.operation()).isEqualTo("EDIT_TIME");
        assertThat(delta.patch().editType()).isEqualTo("KEEP_AND_REPLAN");
        assertThat(delta.changedConstraints().endTime()).isEqualTo("22:00");
        assertThat(delta.changedConstraints().totalMinutes()).isEqualTo(480);
    }
}
