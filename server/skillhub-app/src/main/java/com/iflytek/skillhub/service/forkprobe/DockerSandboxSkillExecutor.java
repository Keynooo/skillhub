package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.AnthropicProperties;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sandboxed skill executor that runs the Claude Code CLI inside a hardened Docker
 * container instead of a bare subprocess.
 * <p>
 * Isolation guarantees (vs {@link ClaudeCodeSubprocessExecutor}):
 * <ul>
 *   <li>read-only root filesystem + tmpfs {@code /tmp}; the skill's SKILL.md is mounted read-only</li>
 *   <li>{@code --cap-drop=ALL} + {@code no-new-privileges}; process runs as nobody (non-root)</li>
 *   <li>per-container {@code --memory}/{@code --cpus}/{@code --pids-limit}</li>
 *   <li>{@code --rm} so the container is destroyed on exit</li>
 * </ul>
 * <p>
 * Concurrency: a <em>global</em> {@link Semaphore} (shared across all comparisons)
 * caps the number of simultaneous containers; each {@code execute()} acquires it with a
 * bounded wait and releases it in a {@code finally}. Because the comparison pool is
 * virtual-thread-per-task, threads blocking on the semaphore don't occupy OS threads.
 * <p>
 * Network note: the CLI must call the LLM API from inside the container, so egress is not
 * disabled by default ({@code --network=bridge}). Full egress isolation would require an
 * allowlist proxy and is a documented follow-up; {@code sandbox-network} is configurable
 * and can be set to {@code none} where such a proxy exists.
 */
class DockerSandboxSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxSkillExecutor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final AnthropicService anthropicService;
    private final AnthropicProperties anthropicProperties;
    private final ForkprobeExecutorProperties properties;
    private final Semaphore sandboxSemaphore;

    DockerSandboxSkillExecutor(AnthropicService anthropicService,
                               AnthropicProperties anthropicProperties,
                               ForkprobeExecutorProperties properties,
                               Semaphore sandboxSemaphore) {
        this.anthropicService = anthropicService;
        this.anthropicProperties = anthropicProperties;
        this.properties = properties;
        this.sandboxSemaphore = sandboxSemaphore;
    }

    @Override
    public SkillResult execute(String skillSystemPrompt, String taskDescription, String skillName,
                               BooleanSupplier cancelled, LlmTarget target) {
        if (cancelled.getAsBoolean()) {
            return new SkillResult("", 0, 0, "已取消");
        }
        Instant start = Instant.now();
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("skillhub-forkprobe-");
            Path skillDir = workspace.resolve("skill");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), skillSystemPrompt, StandardCharsets.UTF_8);

            String prompt = buildTaskPrompt(taskDescription, skillName);
            Path stdoutFile = Files.createTempFile(workspace, "sandbox-out-", ".json");

            // Global container concurrency gate — bound the wait so a saturated pool
            // fails fast with a clear error instead of hanging the comparison forever.
            boolean acquired = sandboxSemaphore.tryAcquire(
                    properties.getTimeoutSeconds() + 30L, TimeUnit.SECONDS);
            if (!acquired) {
                return new SkillResult("", 0, elapsedSeconds(start), "沙盒并发已满，等待超时");
            }

            try {
                Process process = startProcess(skillDir, stdoutFile, target);
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                }

                SkillResult early = awaitCompletion(process, start, cancelled);
                if (early != null) {
                    return early;
                }

                String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
                return parseResult(stdout, elapsedSeconds(start), process.exitValue());
            } finally {
                sandboxSemaphore.release();
            }
        } catch (Exception e) {
            log.warn("Docker sandbox execution failed for '{}': {}", skillName, e.getMessage());
            return new SkillResult("", 0, elapsedSeconds(start), e.getMessage());
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * Wait for the container to finish, bounded by {@code timeoutSeconds}, while
     * honouring the comparison run's cancel flag (destroys the container on cancel).
     * Returns a terminal result if killed, or {@code null} if it exited normally.
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

    @Override
    public boolean verify(String output, String skillName, String approach) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String trimmed = output.trim();
        if (trimmed.startsWith("I cannot") || trimmed.startsWith("I'm unable")
                || trimmed.startsWith("抱歉，我无法") || trimmed.startsWith("对不起")) {
            return false;
        }
        try {
            AnthropicMessageResponse response = anthropicService.sendVerification(output, skillName, approach);
            return response.content().trim().toUpperCase().startsWith("YES");
        } catch (Exception e) {
            log.warn("Skill verification failed for '{}': {}", skillName, e.getMessage());
            return true;
        }
    }

    private Process startProcess(Path skillDir, Path stdoutFile, LlmTarget target) throws IOException {
        List<String> command = buildDockerCommand(skillDir, target);
        if (IS_WINDOWS) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(command);
            command = wrapped;
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(stdoutFile.toFile());
        return pb.start();
    }

    /**
     * Build the full {@code docker run ...} argument list. Package-private so a unit
     * test can assert every isolation flag is present.
     */
    List<String> buildDockerCommand(Path skillDir) {
        return buildDockerCommand(skillDir, null);
    }

    /**
     * Build the full {@code docker run ...} argument list, applying a per-run target
     * override (base URL / API key / model) that wins over the executor-level config.
     */
    List<String> buildDockerCommand(Path skillDir, LlmTarget target) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        // Attach stdin so the prompt written to the docker client's stdin is forwarded
        // to `claude -p` inside the container. Without -i the CLI sees EOF immediately
        // and fails with "Input must be provided either through stdin or as a prompt
        // argument when using --print".
        cmd.add("-i");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,nodev,size=64m");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("--user");
        cmd.add(properties.getSandboxUser());
        cmd.add("--memory");
        cmd.add(properties.getSandboxMemory());
        cmd.add("--cpus");
        cmd.add(properties.getSandboxCpus());
        cmd.add("--pids-limit");
        cmd.add(Integer.toString(properties.getSandboxPidsLimit()));
        cmd.add("--network");
        cmd.add(properties.getSandboxNetwork());
        cmd.add("-e");
        cmd.add("HOME=/tmp");
        // Per-run provider override wins over the deployment default.
        String baseUrl = target != null && target.baseUrl() != null && !target.baseUrl().isBlank()
                ? target.baseUrl() : anthropicProperties.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            cmd.add("-e");
            cmd.add("ANTHROPIC_BASE_URL=" + baseUrl);
        }
        String apiKey = target != null && target.apiKey() != null && !target.apiKey().isBlank()
                ? target.apiKey() : anthropicProperties.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            cmd.add("-e");
            cmd.add("ANTHROPIC_API_KEY=" + apiKey);
        }
        // Forward the model so the CLI inside the container targets the same LLM as the
        // rest of the app (e.g. deepseek-v4-pro on the Anthropic-compatible endpoint). A
        // non-blank forkprobe executor `model` override (--model flag below) still wins.
        String model = target != null && target.model() != null && !target.model().isBlank()
                ? target.model() : anthropicProperties.getModel();
        if (model != null && !model.isBlank()) {
            cmd.add("-e");
            cmd.add("ANTHROPIC_MODEL=" + model);
        }
        cmd.add("-v");
        cmd.add(skillDir.toAbsolutePath() + ":/skill:ro");
        cmd.add(properties.getSandboxImage());

        // Claude Code CLI invocation inside the container.
        cmd.add(properties.getClaudeCliPath());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--add-dir");
        cmd.add("/skill");
        cmd.add("--max-budget-usd");
        cmd.add(Double.toString(properties.getMaxBudgetUsd()));
        cmd.add("--effort");
        cmd.add(properties.getEffort());
        cmd.add("--no-session-persistence");
        if (properties.isDangerouslySkipPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }
        String cliModel = (target != null && target.model() != null && !target.model().isBlank())
                ? target.model() : properties.getModel();
        if (cliModel != null && !cliModel.isBlank()) {
            cmd.add("--model");
            cmd.add(cliModel);
        }
        return cmd;
    }

    private static String buildTaskPrompt(String taskDescription, String skillName) {
        return "容器内 /skill 目录下有一个 SKILL.md 文件，它定义了一个名为 \"" + skillName
                + "\" 的 skill（一套完成任务的方法论/指令）。\n\n"
                + "请遵循以下步骤：\n"
                + "1. 先用 Read 工具读取 /skill/SKILL.md 文件，完整理解它的指令。\n"
                + "2. 严格按 SKILL.md 的方法完成下面的任务，按需使用 Bash/Read/Write/Edit 等工具。\n"
                + "3. 最终只输出任务完成的最终结果本身，不要解释你做了什么，不要复述 SKILL.md 内容。\n\n"
                + "任务：\n" + taskDescription;
    }

    private static SkillResult parseResult(String stdout, float latency, int exitCode) {
        if (stdout == null || stdout.isBlank()) {
            return new SkillResult("", 0, latency,
                    "Claude CLI 无输出（exit=" + exitCode + "）");
        }
        try {
            JsonNode root = objectMapper.readTree(stdout);
            if (root.path("is_error").asBoolean(false)) {
                JsonNode errors = root.path("errors");
                String detail = errors.isArray() && errors.size() > 0
                        ? errors.get(0).asText() : "Claude CLI 执行出错";
                return new SkillResult("", 0, latency, detail);
            }
            String result = root.path("result").asText("");
            int tokens = root.path("usage").path("output_tokens").asInt(0);
            return new SkillResult(result, tokens, latency, result.isBlank() ? "输出为空" : null);
        } catch (Exception e) {
            // stdout is not JSON (e.g. docker/CLI printed a fatal error) — surface it as text.
            return new SkillResult(stdout.trim(), 0, latency, null);
        }
    }

    private static float elapsedSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0f;
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
