package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * Per-candidate review verdict: an overall 0-100 score plus per-dimension breakdown.
 */
public record SkillReview(
        String coordinate,
        int overall,
        List<ReviewScore> dimensions
) {}
