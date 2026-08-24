package com.iflytek.skillhub.dto.forkprobe;

/**
 * Response returned immediately after starting a pipeline (编排) run.
 */
public record PipelineResponse(
        String pipelineId,
        String status,
        String createdAt
) {}
