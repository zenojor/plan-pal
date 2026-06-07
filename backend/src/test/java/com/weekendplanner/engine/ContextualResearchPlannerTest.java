package com.weekendplanner.engine;


import com.weekendplanner.engine.workflow.ContextualResearchPlanner;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.ExperiencePreference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualResearchPlannerTest {

    private final ContextualResearchPlanner planner = new ContextualResearchPlanner();

    @Test
    void ritualPreferenceIsSceneAware() {
        ConstraintSet constraints = ConstraintSet.fromIntent(null)
                .withExperiencePreference(ExperiencePreference.fromPreferenceKey("ritual")
                        .withContext("afternoon", "nearby"));

        ContextualResearchPlanner.SearchPlan datePlan = planner.plan("DATE", constraints);
        ContextualResearchPlanner.SearchPlan familyPlan = planner.plan("FAMILY", constraints);

        assertThat(datePlan.avoidTags()).contains("club", "adult_only");
        assertThat(datePlan.queries()).flatExtracting(ContextualResearchPlanner.SearchQuery::tags)
                .contains("cocktail", "quiet_bar", "exhibition");
        assertThat(familyPlan.avoidTags()).contains("bar", "nightlife", "drinks");
        assertThat(familyPlan.queries()).flatExtracting(ContextualResearchPlanner.SearchQuery::tags)
                .contains("child_friendly", "science", "family_style")
                .doesNotContain("cocktail", "quiet_bar");
    }

    @Test
    void missingContextAsksBeforeResearch() {
        ConstraintSet constraints = ConstraintSet.fromIntent(null)
                .withExperiencePreference(new ExperiencePreference(
                        List.of("ritual"), "low", "polished", "balanced", null,
                        List.of("dessert"), List.of(), null, null));

        ContextualResearchPlanner.SearchPlan plan = planner.plan("DATE", constraints);

        assertThat(plan.needsMoreContext()).isTrue();
        assertThat(plan.clarification()).isNotBlank();
        assertThat(plan.queries()).isEmpty();
    }

    @Test
    void concreteTimeWindowDoesNotRequireTimeHint() {
        ConstraintSet constraints = ConstraintSet.fromIntent(null)
                .withExperiencePreference(new ExperiencePreference(
                        List.of("ritual"), "low", "polished", "balanced", null,
                        List.of("dessert"), List.of(), null, "nearby"))
                .withPlanningContext("14:00", "22:00", 480, 2, "nearby", ExperiencePreference.empty());

        ContextualResearchPlanner.SearchPlan plan = planner.plan("DATE", constraints);

        assertThat(plan.needsMoreContext()).isFalse();
        assertThat(plan.queries()).isNotEmpty();
    }

    @Test
    void weatherSafeAvoidsOutdoor() {
        ConstraintSet constraints = ConstraintSet.fromIntent(null)
                .withExperiencePreference(ExperiencePreference.fromPreferenceKey("weather_safe")
                        .withContext("afternoon", "nearby"));

        ContextualResearchPlanner.SearchPlan datePlan = planner.plan("DATE", constraints);
        ContextualResearchPlanner.SearchPlan familyPlan = planner.plan("FAMILY", constraints);
        ContextualResearchPlanner.SearchPlan generalPlan = planner.plan("SOLO", constraints);

        assertThat(datePlan.avoidTags()).contains("outdoor");
        assertThat(familyPlan.avoidTags()).contains("outdoor");
        assertThat(generalPlan.avoidTags()).contains("outdoor");
    }
}
