package com.weekendplanner.engine.search;

import java.util.List;

public record CandidateSearchRefinement(
        String candidateSetId,
        String targetSegmentId,
        String domain,
        String category,
        List<String> includeTags,
        List<String> excludeTags,
        String budgetLevel,
        String locationScope,
        boolean expandRange
) {
    public CandidateSearchRefinement {
        includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
    }

    public boolean hasMeaningfulConstraint() {
        return category != null && !category.isBlank()
                || budgetLevel != null && !budgetLevel.isBlank()
                || locationScope != null && !locationScope.isBlank()
                || expandRange
                || !includeTags.isEmpty()
                || !excludeTags.isEmpty();
    }
}
