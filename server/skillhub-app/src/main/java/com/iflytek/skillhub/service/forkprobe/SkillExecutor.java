package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.service.AnthropicService;

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
     * @return the execution result
     */
    SkillResult execute(String skillSystemPrompt, String taskDescription, String skillName);

    /**
     * Verify whether the skill's methodology was actually applied in the output.
     *
     * @param output    the generated output text
     * @param skillName the skill display name
     * @param approach  brief description of the skill's approach
     * @return {@code true} if applied, {@code false} if not
     */
    boolean verify(String output, String skillName, String approach);

    /**
     * Result of a single skill execution.
     */
    record SkillResult(
            String output,
            int tokensUsed,
            float latencySeconds,
            String error
    ) {}
}
