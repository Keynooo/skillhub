package com.iflytek.skillhub.dto.forkprobe;

/**
 * Response returned immediately after starting a comparison.
 */
public record CompareResponse(
        String comparisonId,
        String status,
        String createdAt
) {}
