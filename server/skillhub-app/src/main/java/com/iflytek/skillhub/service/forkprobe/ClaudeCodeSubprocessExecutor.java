package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.service.AnthropicService;
import com.iflytek.skillhub.service.AnthropicService.AnthropicMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Phase A skill executor that spawns the Claude Code CLI in a throwaway workspace.
 * <p>
 * Unlike {@link DirectApiSkillExecutor} (prompt-as-skill), this writes the skill's
 * SKILL.md into a workspace and runs {@code claude -p} in real agent mode, so the
 * skill's scripts/tools are actually executed rather than merely simulated.
 * <p>
 * Security note: with {@code dangerouslySkipPermissions} the subprocess can run
 * arbitrary commands. This is acceptable only for trusted (built-in) skills until
 * phase B isolates execution inside a sandbox container.
 */
class ClaudeCodeSubprocessExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeSubprocessExecutor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final AnthropicService anthropicService;
    private final ForkprobeExecutorProperties properties;

    ClaudeCodeSubprocessExecutor(AnthropicService anthropicService,
                                 ForkprobeExecutorProperties properties) {
        this.anthropicService = anthropicService;
        this.properties = properties;
    }

    @Override
    public SkillResult execute(String skillSystemPrompt, String taskDescription, String skillName,
                               BooleanSupplier cancelled, LlmTarget target) {
        Instant start = Instant.now();
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("skillhub-forkprobe-");
            Files.writeString(workspace.resolve("SKILL.md"), skillSystemPrompt, StandardCharsets.UTF_8);

            String prompt = buildTaskPrompt(taskDescription, skillName);
            Path stdoutFile = Files.createTempFile(workspace, "claude-out-", ".json");

            Process process = startProcess(workspace, stdoutFile, target);
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            SkillResult early = awaitCompletion(process, start, cancelled);
            if (early != null) {
                return early;
            }

            String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
            return parseResult(stdout, elapsedSeconds(start), process.exitValue());
        } catch (Exception e) {
            log.warn("Claude CLI execution failed for '{}': {}", skillName, e.getMessage());
            return new SkillResult("", 0, elapsedSeconds(start), e.getMessage());
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * Wait for the subprocess to finish, bounded by {@code timeoutSeconds}, while
     * honouring the comparison run's cancel flag. Returns a terminal result if the
     * process was killed (cancel or timeout), or {@code null} if it exited normally.
     */
    private SkillResult awaitCompletion(Process process, Instant start, BooleanSupplier cancelled)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + properties.getTimeoutSeconds() * 1000L;
        while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            if (cancelled.getAsBoolean()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new SkillResult("", 0, elapsedSeconds(start), "已取消");
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new SkillResult("", 0, elapsedSeconds(start),
                        "执行超时（>" + properties.getTimeoutSeconds() + "s）");
            }
        }
        return null;
    }

    private static float elapsedSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0f;
    }

    @Override
    public Boolean verify(String output, String skillName, String approach, LlmTarget target) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String trimmed = output.trim();
        if (trimmed.startsWith("I cannot") || trimmed.startsWith("I'm unable")
                || trimmed.startsWith("抱歉，我无法") || trimmed.startsWith("对不起")) {
            return false;
        }
        try {
            AnthropicMessageResponse response = target != null && target.hasAny()
                    ? anthropicService.sendVerification(output, skillName, approach,
                            target.model(), target.baseUrl(), target.apiKey())
                    : anthropicService.sendVerification(output, skillName, approach);
            return response.content().trim().toUpperCase().startsWith("YES");
        } catch (Exception e) {
            log.warn("Skill verification failed for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    private Process startProcess(Path workspace, Path stdoutFile, LlmTarget target) throws IOException {
        List<String> command = new ArrayList<>();
        if (IS_WINDOWS) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(properties.getClaudeCliPath());
        command.add("-p");
        command.add("--output-format");
        command.add("json");
        command.add("--add-dir");
        command.add(workspace.toString());
        command.add("--max-budget-usd");
        command.add(Double.toString(properties.getMaxBudgetUsd()));
        command.add("--effort");
        command.add(properties.getEffort());
        command.add("--no-session-persistence");
        if (properties.isDangerouslySkipPermissions()) {
            command.add("--dangerously-skip-permissions");
        }
        // Per-run model override wins; otherwise fall back to the executor-level model.
        String cliModel = (target != null && target.model() != null && !target.model().isBlank())
                ? target.model() : properties.getModel();
        if (cliModel != null && !cliModel.isBlank()) {
            command.add("--model");
            command.add(cliModel);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        // Per-run provider override: point the subprocess at a different
        // Anthropic-compatible endpoint + key, matching what direct-api does.
        if (target != null) {
            if (target.baseUrl() != null && !target.baseUrl().isBlank()) {
                pb.environment().put("ANTHROPIC_BASE_URL", target.baseUrl());
            }
            if (target.apiKey() != null && !target.apiKey().isBlank()) {
                pb.environment().put("ANTHROPIC_API_KEY", target.apiKey());
            }
        }
        // Run the CLI inside the throwaway workspace so that the prompt's
        // "当前工作目录里的 SKILL.md" is resolvable, and any files the skill
        // writes land in the workspace (cleaned up) rather than the server's cwd.
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(stdoutFile.toFile());
        return pb.start();
    }

    private static String buildTaskPrompt(String taskDescription, String skillName) {
        return ForkprobeAgentContext.SANDBOX_CONTEXT
                + "当前工作目录里有一个 SKILL.md 文件，它定义了一个名为 \"" + skillName
                + "\" 的 skill（一套完成任务的方法论/指令）。\n\n"
                + "请遵循以下步骤：\n"
                + "1. 先用 Read 工具读取工作目录下的 SKILL.md 文件，完整理解它的指令。\n"
                + "2. 严格按 SKILL.md 的方法完成下面的任务，按需使用 Bash/Read/Write/Edit 等工具。\n"
                + "3. 最终只输出任务完成的最终结果本身，不要解释你做了什么，不要复述 SKILL.md 内容。\n\n"
                + "任务：\n" + taskDescription;
    }

    private static SkillResult parseResult(String stdout, float latency, int exitCode) {
        if (stdout == null || stdout.isBlank()) {
            return new SkillResult("", 0, latency,
                    "Claude CLI 无输出（exit=" + exitCode + "）");
        }
        JsonNode root = CliResultParser.extractResultEnvelope(objectMapper, stdout);
        if (root == null) {
            // stdout is not JSON (e.g. CLI printed a fatal error) — surface it as text.
            return new SkillResult(CliResultParser.stripWarningPrefixes(stdout).trim(),
                    0, latency, null);
        }
        if (root.path("is_error").asBoolean(false)) {
            JsonNode errors = root.path("errors");
            String detail = errors.isArray() && errors.size() > 0
                    ? errors.get(0).asText() : "Claude CLI 执行出错";
            return new SkillResult("", 0, latency, detail);
        }
        String result = root.path("result").asText("");
        int tokens = root.path("usage").path("output_tokens").asInt(0);
        return new SkillResult(result, tokens, latency, result.isBlank() ? "输出为空" : null);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up workspace {}: {}", dir, e.getMessage());
        }
    }
}
