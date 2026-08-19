package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.dto.forkprobe.OutputFile;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sandboxed skill executor that runs the Claude Code CLI inside a hardened Docker
 * container instead of a bare subprocess.
 * <p>
 * Isolation guarantees (vs {@link ClaudeCodeSubprocessExecutor}):
 * <ul>
 *   <li>read-only root filesystem + tmpfs {@code /tmp}; the skill's SKILL.md is delivered inline in the prompt (no bind-mount, which the host daemon could not resolve under DooD)</li>
 *   <li>{@code --cap-drop=ALL} + {@code no-new-privileges}; process runs as nobody (non-root)</li>
 *   <li>per-container {@code --memory}/{@code --cpus}/{@code --pids-limit}</li>
 * </ul>
 * <p>
 * Deliverable file transfer: a per-run named Docker volume is mounted at {@code /output}
 * (a named volume, not a bind-mount, so it resolves daemon-side under DooD). The agent is
 * instructed to write any deliverable files there. After the CLI exits, {@code docker cp}
 * copies those files out of the stopped container before {@code docker rm -f} removes it,
 * and they are base64-encoded into the result for download. The volume is created and
 * made world-writable (the sandbox image runs as nobody) by a one-shot root container.
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

    /** In-container directory the agent is told to write deliverable files into. */
    private static final String OUTPUT_DIR = "/output";

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
        String runId = UUID.randomUUID().toString().substring(0, 12);
        String containerName = "skillhub-sb-" + runId;
        String volumeName = "skillhub-out-" + runId;
        try {
            workspace = Files.createTempDirectory("skillhub-forkprobe-");

            String prompt = buildTaskPrompt(skillSystemPrompt, taskDescription, skillName);
            Path stdoutFile = Files.createTempFile(workspace, "sandbox-out-", ".json");

            // Global container concurrency gate — bound the wait so a saturated pool
            // fails fast with a clear error instead of hanging the comparison forever.
            boolean acquired = sandboxSemaphore.tryAcquire(
                    properties.getTimeoutSeconds() + 30L, TimeUnit.SECONDS);
            if (!acquired) {
                return new SkillResult("", 0, elapsedSeconds(start), "沙盒并发已满，等待超时");
            }

            try {
                prepareOutputVolume(volumeName);
                Process process = startProcess(containerName, volumeName, stdoutFile, target);
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                }

                SkillResult early = awaitCompletion(process, start, cancelled);
                if (early != null) {
                    return early;
                }

                // Extract deliverable files from the /output volume before the container is removed.
                List<OutputFile> files = extractOutputFiles(containerName, workspace);

                String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
                return parseResult(stdout, elapsedSeconds(start), process.exitValue(), files);
            } finally {
                sandboxSemaphore.release();
            }
        } catch (Exception e) {
            log.warn("Docker sandbox execution failed for '{}': {}", skillName, e.getMessage());
            return new SkillResult("", 0, elapsedSeconds(start), e.getMessage());
        } finally {
            // Always remove the container and its output volume (there is no --rm, so we
            // own their lifecycle). docker rm -f also reclaims a container orphaned by a
            // forcible client kill on the cancel/timeout path.
            runDocker(List.of("docker", "rm", "-f", containerName));
            runDocker(List.of("docker", "volume", "rm", volumeName));
            deleteRecursively(workspace);
        }
    }

    /**
     * Create the per-run output volume and make its root world-writable so the sandbox
     * process (which runs as nobody) can write deliverable files. A freshly created
     * volume is root-owned 0755, so a one-shot root container chmods it to 1777.
     */
    private void prepareOutputVolume(String volumeName) {
        runDocker(List.of("docker", "volume", "create", volumeName));
        runDocker(List.of("docker", "run", "--rm", "--user", "0:0",
                "-v", volumeName + ":" + OUTPUT_DIR,
                properties.getSandboxImage(), "chmod", "1777", OUTPUT_DIR));
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

    private Process startProcess(String containerName, String volumeName, Path stdoutFile, LlmTarget target)
            throws IOException {
        // Invoke `docker` directly instead of wrapping it in `cmd /c`. On Windows,
        // `cmd /c` routes the CLI's UTF-8 stdin/stdout through the console codepage
        // (CP936 on Chinese systems), garbling non-ASCII in the captured result.
        // `docker` is a native executable (docker.exe) on both Windows and Linux, so
        // no shell is needed and the I/O stays byte-transparent UTF-8.
        List<String> command = buildDockerCommand(target, containerName, volumeName);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(stdoutFile.toFile());
        return pb.start();
    }

    /**
     * Build the full {@code docker run ...} argument list. Package-private so a unit
     * test can assert every isolation flag is present.
     */
    List<String> buildDockerCommand() {
        return buildDockerCommand(null, "skillhub-sb-test", "skillhub-out-test");
    }

    List<String> buildDockerCommand(LlmTarget target) {
        return buildDockerCommand(target, "skillhub-sb-test", "skillhub-out-test");
    }

    /**
     * Build the full {@code docker run ...} argument list, applying a per-run target
     * override (base URL / API key / model) that wins over the executor-level config.
     */
    List<String> buildDockerCommand(LlmTarget target, String containerName, String volumeName) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        // No `--rm`: deliverable files are extracted via `docker cp` after the CLI exits
        // but before removal. execute()'s finally block owns cleanup.
        cmd.add("--name");
        cmd.add(containerName);
        // Attach stdin so the prompt written to the docker client's stdin is forwarded
        // to `claude -p` inside the container. Without -i the CLI sees EOF immediately
        // and fails with "Input must be provided either through stdin or as a prompt
        // argument when using --print".
        cmd.add("-i");
        // Start the CLI in the writable tmpfs so the agent's tools have a working
        // directory they can write to (the image WORKDIR /skill is read-only here).
        cmd.add("-w");
        cmd.add("/tmp");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,nodev,size=64m");
        // Deliverable files: a named volume (daemon-side, DooD-safe) mounted at /output.
        cmd.add("-v");
        cmd.add(volumeName + ":" + OUTPUT_DIR);
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
        cmd.add(properties.getSandboxImage());

        // Claude Code CLI invocation inside the container. The skill methodology is
        // delivered inline in the stdin prompt (see buildTaskPrompt), so there is no
        // bind-mount — a bind-mount source would be a container-local path the host
        // daemon cannot see under Docker-out-of-Docker.
        cmd.add(properties.getClaudeCliPath());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
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

    static String buildTaskPrompt(String skillSystemPrompt, String taskDescription, String skillName) {
        return "下面是一个名为 \"" + skillName + "\" 的 skill 的方法论（即其 SKILL.md 的完整内容）：\n\n"
                + "```markdown\n" + skillSystemPrompt + "\n```\n\n"
                + "请按以下步骤完成任务：\n"
                + "1. 先完整理解上面这套方法论。\n"
                + "2. 严格按该方法论完成下面的任务，按需使用 Bash/Read/Write/Edit 等工具。\n"
                + "3. 如果任务产出的是可交付的文件（如报告、图片、图表、代码、数据表格等），请把这些文件用 Write 工具写入 "
                + OUTPUT_DIR + " 目录，使用清晰的文件名；如果只是纯文本回答，则无需写文件。\n"
                + "4. 最终只输出任务完成的最终结果本身，不要解释你做了什么，不要复述方法论内容；"
                + "若你写入了文件，请在结尾单独列出这些文件名。\n\n"
                + "任务：\n" + taskDescription;
    }

    /**
     * Copy the skill's deliverable files out of the sandbox via {@code docker cp} and
     * base64-encode them for the response. Runs after the container has exited but
     * before it is removed; the {@code /output} named volume persists until removal,
     * so its contents remain readable from the stopped container.
     */
    private List<OutputFile> extractOutputFiles(String containerName, Path workspace) {
        Path outputDir = workspace.resolve("output");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return List.of();
        }
        runDocker(List.of("docker", "cp", containerName + ":" + OUTPUT_DIR + "/.", outputDir.toString()));

        List<OutputFile> files = new ArrayList<>();
        long maxBytes = properties.getSandboxOutputMaxFileBytes();
        try (var walk = Files.walk(outputDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    long size = Files.size(file);
                    if (size <= 0 || size > maxBytes) {
                        log.warn("Skipping sandbox output file '{}' ({} bytes, cap {})",
                                file.getFileName(), size, maxBytes);
                        return;
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    files.add(new OutputFile(
                            file.getFileName().toString(),
                            size,
                            probeContentType(file),
                            Base64.getEncoder().encodeToString(bytes)));
                } catch (IOException e) {
                    log.warn("Failed to read sandbox output file '{}': {}", file.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk sandbox output dir: {}", e.getMessage());
        }
        return files;
    }

    private static String probeContentType(Path file) {
        try {
            String type = Files.probeContentType(file);
            if (type != null && !type.isBlank()) {
                return type;
            }
        } catch (IOException ignored) {
        }
        return "application/octet-stream";
    }

    /**
     * Run a best-effort {@code docker ...} housekeeping command (volume create/chmod,
     * cp, rm). Failures are logged at debug: cleanup of a never-created resource, or an
     * empty output dir, is expected and not worth a warning.
     */
    private void runDocker(List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("docker {} failed: {}", args.get(0), e.getMessage());
        }
    }

    private static SkillResult parseResult(String stdout, float latency, int exitCode, List<OutputFile> files) {
        if (stdout == null || stdout.isBlank()) {
            return new SkillResult("", 0, latency,
                    "Claude CLI 无输出（exit=" + exitCode + "）", files);
        }
        try {
            JsonNode root = objectMapper.readTree(stdout);
            if (root.path("is_error").asBoolean(false)) {
                JsonNode errors = root.path("errors");
                String detail = errors.isArray() && errors.size() > 0
                        ? errors.get(0).asText() : "Claude CLI 执行出错";
                return new SkillResult("", 0, latency, detail, files);
            }
            String result = root.path("result").asText("");
            int tokens = root.path("usage").path("output_tokens").asInt(0);
            return new SkillResult(result, tokens, latency, result.isBlank() ? "输出为空" : null, files);
        } catch (Exception e) {
            // stdout is not JSON (e.g. docker/CLI printed a fatal error) — surface it as text.
            return new SkillResult(stdout.trim(), 0, latency, null, files);
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
