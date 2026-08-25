package com.iflytek.skillhub.dto.forkprobe;

/**
 * A single recommended skill candidate.
 */
public record RecommendedSkill(
        /** Full coordinate like "global/academic-writing". */
        String coordinate,

        /** Skill display name. */
        String name,

        /** Namespace name (without @ prefix). */
        String namespace,

        /** Human-readable reason for the recommendation (Chinese). */
        String reasonZh,

        /** The domain/category this skill belongs to. */
        String domain,

        /** Source: "catalog", "skillhub", or "baseline". */
        String source,

        /** Approximate GitHub stars (0 for non-GitHub skills). */
        int stars,

        /** GitHub source URL for catalog skills; null for SkillHub skills / baseline. */
        String sourceUrl,

        /**
         * True when the skill's SKILL.md drives the agent to the live web
         * (search/fetch/crawl). The comparison sandbox runs on a direct bridge
         * network with no proxy, so these skills are prone to slow runs or
         * timeouts — the UI shows a "需联网" badge.
         */
        boolean needsNetwork
) {
    /** Copy with a different needsNetwork flag. */
    public RecommendedSkill withNeedsNetwork(boolean value) {
        return new RecommendedSkill(coordinate, name, namespace, reasonZh, domain, source,
                stars, sourceUrl, value);
    }
}
