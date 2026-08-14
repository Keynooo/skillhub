package com.iflytek.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the forkprobe skill execution backend.
 * <p>
 * Controls whether comparisons run skills via the Anthropic Messages API directly
 * (prompt-as-skill, cheap, no tools) or via the Claude Code CLI subprocess
 * (real agent execution with tool access, expensive, needs the {@code claude} binary).
 */
@ConfigurationProperties(prefix = "skillhub.forkprobe.executor")
public class ForkprobeExecutorProperties {

    /**
     * Execution backend. {@code direct-api} (default) keeps the original
     * prompt-as-skill behaviour; {@code claude-cli} spawns the Claude Code CLI
     * for real agent-mode execution.
     */
    private String mode = "direct-api";

    /** Path or name of the Claude Code CLI binary (default resolves from PATH). */
    private String claudeCliPath = "claude";

    /** Per-skill execution timeout in seconds. */
    private int timeoutSeconds = 300;

    /** Maximum USD budget per skill execution (only applies to claude-cli mode). */
    private double maxBudgetUsd = 1.0;

    /** Effort level for the CLI session: low, medium, high, xhigh, max. */
    private String effort = "low";

    /** Optional model override. Empty means use the CLI's own default configuration. */
    private String model = "";

    /**
     * Bypass all permission checks in the subprocess so skills can actually run
     * tools (Bash/Read/Write/Edit). Only safe while execution is not container-isolated
     * and restricted to trusted (built-in) skills; phase B moves this behind a sandbox.
     */
    private boolean dangerouslySkipPermissions = true;

    // --- Docker sandbox mode (mode=docker) ---

    /** Docker image used as the sandbox runtime. */
    private String sandboxImage = "skillhub-sandbox:latest";

    /** Docker network mode for the sandbox container. Default {@code bridge} keeps LLM
     *  API egress available; set {@code none} where an egress allowlist proxy is in place. */
    private String sandboxNetwork = "bridge";

    /** Memory limit per sandbox container ({@code docker run --memory}). */
    private String sandboxMemory = "512m";

    /** CPU limit per sandbox container ({@code docker run --cpus}). */
    private String sandboxCpus = "1.0";

    /** Process count limit per sandbox container ({@code docker run --pids-limit}). */
    private int sandboxPidsLimit = 256;

    /** UID:GID the container process runs as (nobody, non-root). */
    private String sandboxUser = "65534:65534";

    /** Maximum number of sandbox containers across all comparisons (global concurrency cap). */
    private int sandboxMaxConcurrency = 4;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getClaudeCliPath() {
        return claudeCliPath;
    }

    public void setClaudeCliPath(String claudeCliPath) {
        this.claudeCliPath = claudeCliPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    public void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isDangerouslySkipPermissions() {
        return dangerouslySkipPermissions;
    }

    public void setDangerouslySkipPermissions(boolean dangerouslySkipPermissions) {
        this.dangerouslySkipPermissions = dangerouslySkipPermissions;
    }

    public String getSandboxImage() {
        return sandboxImage;
    }

    public void setSandboxImage(String sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    public String getSandboxNetwork() {
        return sandboxNetwork;
    }

    public void setSandboxNetwork(String sandboxNetwork) {
        this.sandboxNetwork = sandboxNetwork;
    }

    public String getSandboxMemory() {
        return sandboxMemory;
    }

    public void setSandboxMemory(String sandboxMemory) {
        this.sandboxMemory = sandboxMemory;
    }

    public String getSandboxCpus() {
        return sandboxCpus;
    }

    public void setSandboxCpus(String sandboxCpus) {
        this.sandboxCpus = sandboxCpus;
    }

    public int getSandboxPidsLimit() {
        return sandboxPidsLimit;
    }

    public void setSandboxPidsLimit(int sandboxPidsLimit) {
        this.sandboxPidsLimit = sandboxPidsLimit;
    }

    public String getSandboxUser() {
        return sandboxUser;
    }

    public void setSandboxUser(String sandboxUser) {
        this.sandboxUser = sandboxUser;
    }

    public int getSandboxMaxConcurrency() {
        return sandboxMaxConcurrency;
    }

    public void setSandboxMaxConcurrency(int sandboxMaxConcurrency) {
        this.sandboxMaxConcurrency = sandboxMaxConcurrency;
    }
}
