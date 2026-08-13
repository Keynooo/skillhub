package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.dto.forkprobe.ReviewResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for a single comparison run.
 * <p>
 * Lives in {@link ForkprobeComparisonService#comparisons} with a 30-minute TTL.
 * Not persisted to database — restarting the server clears in-flight comparisons.
 */
class ComparisonRun {

    enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final String comparisonId;
    private volatile Status status;
    private final String taskDescription;
    private final List<SkillSpec> skills;
    private final Map<String, ComparisonResult> results;
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String error;
    private volatile ReviewResult review;

    ComparisonRun(String taskDescription, List<SkillSpec> skills) {
        this.comparisonId = UUID.randomUUID().toString();
        this.status = Status.PENDING;
        this.taskDescription = taskDescription;
        this.skills = List.copyOf(skills);
        this.results = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
    }

    String getComparisonId() {
        return comparisonId;
    }

    Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
        if (status == Status.RUNNING && this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        if (status == Status.COMPLETED || status == Status.FAILED) {
            this.completedAt = Instant.now();
        }
    }

    String getTaskDescription() {
        return taskDescription;
    }

    List<SkillSpec> getSkills() {
        return skills;
    }

    Map<String, ComparisonResult> getResults() {
        return results;
    }

    void addResult(String skillCoordinate, ComparisonResult result) {
        results.put(skillCoordinate, result);
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    String getError() {
        return error;
    }

    void setError(String error) {
        this.error = error;
    }

    ReviewResult getReview() {
        return review;
    }

    void setReview(ReviewResult review) {
        this.review = review;
    }

    /**
     * A skill specification for comparison execution.
     */
    record SkillSpec(
            String coordinate,   // "namespace/slug" or "baseline"
            String name,
            String namespace,
            String systemPrompt   // SKILL.md body content or baseline prompt
    ) {}
}
