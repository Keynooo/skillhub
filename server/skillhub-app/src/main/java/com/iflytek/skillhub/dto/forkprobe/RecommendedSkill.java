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
        int stars
) {}
