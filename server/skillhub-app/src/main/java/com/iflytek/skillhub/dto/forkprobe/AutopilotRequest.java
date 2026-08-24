package com.iflytek.skillhub.dto.forkprobe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body to start an autopilot (AI-orchestrated) pipeline run.
 * <p>
 * Unlike {@link PipelineRequest}, the user does not assemble parallel lanes — they
 * nominate a single pool of 0..5 candidate skill coordinates, and the orchestrator
 * (an LLM aware of each candidate's SKILL.md body) decides which to actually use,
 * in what order. An empty pool means the raw native output (pure baseline).
 */
public record AutopilotRequest(
        @NotBlank(message = "任务描述不能为空")
        String taskDescription,

        /**
         * The user's nominated candidate skill pool (0..5). The AI orchestrator selects
         * the subset to use and their execution order. Empty = pure baseline run.
         */
        @Size(max = 5, message = "最多选择 5 个候选 skill")
        List<String> skillCoordinates,

        /**
         * Optional per-run provider id (e.g. {@code glm}, {@code local}). Blank/null or
         * {@code "default"} means the deployment's configured model.
         */
        String provider
) {}
