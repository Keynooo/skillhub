package com.iflytek.skillhub.search;

import java.util.List;

/**
 * Read-side contract for executing skill searches against the configured search backend.
 */
public interface SearchQueryService {
    SearchResult search(SearchQuery query);

    /**
     * Returns skill ids ranked by semantic similarity to the given skill,
     * excluding the skill itself, honoring the caller's visibility scope.
     */
    List<Long> findSimilarSkillIds(Long skillId, int limit, SearchVisibilityScope scope);

    /**
     * Returns skill ids ranked by semantic similarity to a free-form query text
     * (e.g. a task description), honoring the caller's visibility scope.
     * Deterministic: the same text yields the same ordering.
     */
    List<Long> findSimilarByText(String text, int limit, SearchVisibilityScope scope);
}
