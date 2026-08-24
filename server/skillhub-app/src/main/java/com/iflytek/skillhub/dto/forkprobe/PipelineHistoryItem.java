package com.iflytek.skillhub.dto.forkprobe;

/**
 * Summary entry for a persisted forkprobe pipeline (编排) run, shown in the
 * history list. The full stage results are fetched lazily via the
 * history-detail endpoint.
 */
public record PipelineHistoryItem(
        String pipelineId,
        String taskDescription,
        String status,          // COMPLETED | FAILED | CANCELLED
        String provider,        // null = deployment default
        int stageCount,         // total skills across all three lanes
        String createdAt,
        String completedAt
) {}
