package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * One parallel lane of a pipeline (编排) run: a serial chain of stages whose
 * outputs feed each other. Lanes run concurrently and are displayed side by side.
 */
public record PipelineLaneResult(
        int index,          // 0-based lane number (0, 1, 2)
        String status,      // lane-level: PENDING | RUNNING | COMPLETED | FAILED | SKIPPED
        List<PipelineStageResult> stages
) {}
