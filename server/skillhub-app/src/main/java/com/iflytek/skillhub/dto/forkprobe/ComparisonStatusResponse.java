package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * Response returned when polling the comparison status endpoint.
 */
public record ComparisonStatusResponse(
        String comparisonId,
        String status,          // PENDING | RUNNING | COMPLETED | FAILED
        List<CandidateResult> results,
        String error,
        String startedAt,
        String completedAt,
        ReviewResult review     // null until the AI judge pass finishes (or if review failed/disabled)
) {}
