package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.mock.MockPoiDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateRefinementSearchTest {

    private final ReplacementSearchEngine searchEngine = new ReplacementSearchEngine(new MockPoiDatabase());

    @Test
    void strictHotpotRefinementOnlyReturnsHotpotCandidates() {
        List<PoiDto> candidates = searchEngine.findCandidates("DINING",
                refinementPatch(List.of("STRICT_TAGS", "hotpot")),
                intent(),
                Set.of("P001"),
                3);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(poi ->
                assertThat(String.join(" ", poi.tags()).toLowerCase()).contains("hotpot"));
    }

    @Test
    void strictUnknownRefinementDoesNotFallbackToUnrelatedCandidates() {
        List<PoiDto> candidates = searchEngine.findCandidates("DINING",
                refinementPatch(List.of("STRICT_TAGS", "nonexistent_special_tag")),
                intent(),
                Set.of(),
                3);

        assertThat(candidates).isEmpty();
    }

    @Test
    void strictDrinksRefinementOnlyReturnsBarCandidates() {
        List<PoiDto> candidates = searchEngine.findCandidates("DRINKS",
                refinementPatch("DRINKS", List.of("STRICT_TAGS", "bar")),
                intent(),
                Set.of(),
                3);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(poi -> {
            assertThat(poi.category()).isEqualTo("RESTAURANT");
            assertThat(String.join(" ", poi.tags()).toLowerCase()).contains("bar");
        });
    }

    private PlanPatch refinementPatch(List<String> prefer) {
        return refinementPatch("DINING", prefer);
    }

    private PlanPatch refinementPatch(String phase, List<String> prefer) {
        return new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target("seg-1", null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), prefer, null, null, null, false),
                true);
    }

    private PlanIntent intent() {
        return new PlanIntent(3, List.of(), "14:00", "18:00", 240,
                "SOCIAL", List.of("DINING"), List.of(), null, "NEARBY",
                "candidate refinement");
    }
}
