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
}
