package com.iflytek.skillhub.service.forkprobe;

/**
 * Result of executing a single skill against a task.
 */
class ComparisonResult {

    private final String skillCoordinate;
    private final String skillName;
    private String output;
    private int tokensUsed;
    private float latencySeconds;
    private Boolean skillApplied; // null = not verified yet, true = ✅, false = ⚠️
    private String appliedReason;
    private String error;

    ComparisonResult(String skillCoordinate, String skillName) {
        this.skillCoordinate = skillCoordinate;
        this.skillName = skillName;
    }

    String getSkillCoordinate() {
        return skillCoordinate;
    }

    String getSkillName() {
        return skillName;
    }

    String getOutput() {
        return output;
    }

    void setOutput(String output) {
        this.output = output;
    }

    int getTokensUsed() {
        return tokensUsed;
    }

    void setTokensUsed(int tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    float getLatencySeconds() {
        return latencySeconds;
    }

    void setLatencySeconds(float latencySeconds) {
        this.latencySeconds = latencySeconds;
    }

    Boolean getSkillApplied() {
        return skillApplied;
    }

    void setSkillApplied(Boolean skillApplied) {
        this.skillApplied = skillApplied;
    }

    String getAppliedReason() {
        return appliedReason;
    }

    void setAppliedReason(String appliedReason) {
        this.appliedReason = appliedReason;
    }

    String getError() {
        return error;
    }

    void setError(String error) {
        this.error = error;
    }

    boolean isCompleted() {
        return output != null || error != null;
    }
}
