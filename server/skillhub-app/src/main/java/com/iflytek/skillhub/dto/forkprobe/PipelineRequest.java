package com.iflytek.skillhub.dto.forkprobe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body to start a skill pipeline (编排) run.
 */
public record PipelineRequest(
        @NotBlank(message = "任务描述不能为空")
        String taskDescription,

        /**
         * One to three parallel lanes. Each lane is an <em>unordered</em> list of skill
         * coordinates the user picked (0..5 each); the system auto-orders them so each
         * skill's output feeds the next within that lane. Format: "namespace/slug"
         * (e.g. "global/academic-writing"). An <em>empty</em> lane is the native/baseline
         * reference — the raw task run with no skill loaded. No skill is dropped.
         */
        @NotEmpty(message = "至少需要一条通道")
        @Size(min = 1, max = 3, message = "必须是 1~3 条通道")
        List<List<String>> lanes,

        /**
         * Optional per-run provider id (e.g. {@code glm}, {@code local}) selected from
         * the configured {@code skillhub.anthropic.providers} map. Blank/null or
         * {@code "default"} means use the deployment's configured model.
         */
        String provider
) {}
