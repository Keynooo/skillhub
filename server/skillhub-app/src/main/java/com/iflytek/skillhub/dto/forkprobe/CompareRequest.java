package com.iflytek.skillhub.dto.forkprobe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body to start a skill comparison run.
 */
public record CompareRequest(
        @NotBlank(message = "任务描述不能为空")
        String taskDescription,

        /**
         * List of skill coordinates to compare.
         * Format: "namespace/slug" (e.g. "global/academic-writing") or "baseline".
         */
        @NotEmpty(message = "至少需要选择一个 skill")
        @Size(max = 5, message = "一次最多对比 5 个 skill")
        List<String> skillCoordinates,

        /**
         * Optional per-run provider id (e.g. {@code glm}, {@code local}) selected from
         * the configured {@code skillhub.anthropic.providers} map. Blank/null or
         * {@code "default"} means use the deployment's configured model.
         */
        String provider
) {}
