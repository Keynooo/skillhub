package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * Response returned when polling the pipeline (编排) status endpoint.
 */
public record PipelineStatusResponse(
        String pipelineId,
        String status,          // PENDING | RUNNING | COMPLETED | FAILED | CANCELLED
        List<PipelineLaneResult> lanes,
        String error,
        String startedAt,
        String completedAt
) {}
