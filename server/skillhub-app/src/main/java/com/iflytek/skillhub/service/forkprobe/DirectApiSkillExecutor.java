package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.service.AnthropicService;
import com.iflytek.skillhub.service.AnthropicService.AnthropicMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Phase 1 skill executor that calls the Anthropic Messages API directly.
 * <p>
 * The skill's SKILL.md content is injected as the system prompt. This is
 * "prompt-as-skill" mode — no agent loop, no tool calling. Sufficient for
 * text-based skills like academic writing, translation, summarization, etc.
 * <p>
 * Phase 2 can add a {@code ClaudeCodeSubprocessExecutor} that spawns the
 * {@code claude} CLI for full agent-mode execution with tool support.
 */
class DirectApiSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(DirectApiSkillExecutor.class);

    static final String BASELINE_PROMPT =
            "You are a helpful assistant. Complete the user's task to the best of your ability. " +
            "Do not apply any specialized framework or skill — just respond naturally.";

    private final AnthropicService anthropicService;
    private final int maxTokens;

    DirectApiSkillExecutor(AnthropicService anthropicService, int maxTokens) {
        this.anthropicService = anthropicService;
        this.maxTokens = maxTokens;
    }

    @Override
    public SkillResult execute(String skillSystemPrompt, String taskDescription, String skillName,
                               BooleanSupplier cancelled, LlmTarget target) {
        if (cancelled.getAsBoolean()) {
            return new SkillResult("", 0, 0, "已取消");
        }
        Instant start = Instant.now();
        try {
            AnthropicMessageResponse response;
            if (target == null || !target.hasAny()) {
                response = anthropicService.sendMessageWithRetry(
                        skillSystemPrompt, taskDescription, maxTokens, 0);
            } else {
                response = anthropicService.sendMessageWithRetry(
                        skillSystemPrompt, taskDescription, maxTokens, 0,
                        target.model(), target.baseUrl(), target.apiKey());
            }
            return new SkillResult(response.content(), response.tokensUsed(), response.latencySeconds(), null);
        } catch (Exception e) {
            log.warn("Skill execution failed for '{}': {}", skillName, e.getMessage());
            float latency = (System.currentTimeMillis() - start.toEpochMilli()) / 1000.0f;
            return new SkillResult("", 0, latency, e.getMessage());
        }
    }

    @Override
    public Boolean verify(String output, String skillName, String approach) {
        if (output == null || output.isBlank()) {
            return false;
        }

        // Quick heuristic: only reject clear refusals. Do NOT gate on output length —
        // a legitimate short result (e.g. a single polished sentence) is a valid skill
        // application and must reach the LLM verification below.
        String trimmed = output.trim();
        if (trimmed.startsWith("I cannot") || trimmed.startsWith("I'm unable")
                || trimmed.startsWith("抱歉，我无法") || trimmed.startsWith("对不起")) {
            return false;
        }

        // LLM-based verification for ambiguous cases
        try {
            AnthropicMessageResponse response = anthropicService.sendVerification(output, skillName, approach);
            String verdict = response.content().trim().toUpperCase();
            return verdict.startsWith("YES");
        } catch (Exception e) {
            log.warn("Skill verification failed for '{}': {}", skillName, e.getMessage());
            // Verification is best-effort. A failed call must not claim "applied"
            // (nor "not applied") — return null so the caller shows no badge.
            return null;
        }
    }
}
