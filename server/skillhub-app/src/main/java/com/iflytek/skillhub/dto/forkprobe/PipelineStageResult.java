package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * Result for a single stage within a pipeline (编排) run.
 */
public record PipelineStageResult(
        int index,              // 0-based index within its lane
        String skillCoordinate,
        String skillName,
        String status,          // PENDING | RUNNING | COMPLETED | FAILED | SKIPPED
        String output,          // null until the stage completes
        int tokensUsed,
        float latencySeconds,

        /** null = not verified, true = ✅ skill applied, false = ⚠️ skill not applied */
        Boolean skillApplied,
        String appliedReason,
        String error,

        /** GitHub source URL for catalog skills; null for SkillHub skills. */
        String sourceUrl,

        /** Deliverable files the skill wrote to the sandbox /output dir (base64-encoded). */
        List<OutputFile> files,

        /** Truncated handoff input fed to this stage, for the summary view. */
        String inputPreview
) {}
