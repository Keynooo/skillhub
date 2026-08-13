package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * The independent AI judge's verdict for a completed comparison run.
 * <p>
 * This recommendation is produced by a separate review pass over the candidate
 * outputs and is intentionally independent of the user's own winner selection.
 */
public record ReviewResult(
        String winnerCoordinate,
        String winnerReason,
        List<SkillReview> scores
) {}
