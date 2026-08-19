package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.dto.forkprobe.OutputFile;
import com.iflytek.skillhub.service.AnthropicService;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Executes a single skill against a task description within the comparison sandbox.
 * <p>
 * Phase 1 implementation: {@link DirectApiSkillExecutor} uses the Anthropic Messages API directly.
 * Phase 2 could add {@code ClaudeCodeSubprocessExecutor} for full agent-mode execution.
 */
interface SkillExecutor {

    /**
     * Execute a skill's system prompt against a task description.
     *
     * @param skillSystemPrompt the SKILL.md body content (or baseline default prompt)
     * @param taskDescription   the user's task description
     * @param skillName         display name for logging/error context
     * @param cancelled         supplies {@code true} once the owning comparison run has been
     *                          cancelled; executors should stop promptly and destroy any process
     * @param target            optional per-run provider/model override; {@code null} or blank
     *                          fields mean the executor's defaults
     * @return the execution result
     */
    SkillResult execute(String skillSystemPrompt, String taskDescription, String skillName,
                        BooleanSupplier cancelled, LlmTarget target);

    /**
     * Verify whether the skill's methodology was actually applied in the output.
     *
     * @param output    the generated output text
     * @param skillName the skill display name
     * @param approach  brief description of the skill's approach
     * @param target    optional per-run provider/model override; verification should
     *                  run against the same provider that produced the output, or the
     *                  executor default when {@code null}/blank
     * @return {@code Boolean.TRUE} if applied, {@code Boolean.FALSE} if clearly not,
     *         or {@code null} if verification could not be completed — the caller then
     *         renders "unknown" instead of a misleading applied/not-applied badge
     */
    Boolean verify(String output, String skillName, String approach, LlmTarget target);

    /**
     * Result of a single skill execution.
     */
    record SkillResult(
            String output,
            int tokensUsed,
            float latencySeconds,
            String error,
            List<OutputFile> files
    ) {
        /** Convenience constructor: no deliverable files (prompt-as-skill / subprocess paths). */
        SkillResult(String output, int tokensUsed, float latencySeconds, String error) {
            this(output, tokensUsed, latencySeconds, error, List.of());
        }
    }
}
