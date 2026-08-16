package com.iflytek.skillhub.dto.forkprobe;

/**
 * Summary entry for a persisted forkprobe comparison run, shown in the history
 * list. The full results are fetched lazily via the history-detail endpoint.
 */
public record ComparisonHistoryItem(
        String comparisonId,
        String taskDescription,
        String status,          // COMPLETED | FAILED | CANCELLED
        String provider,        // null = deployment default
        int skillCount,
        String createdAt,
        String completedAt
) {}
