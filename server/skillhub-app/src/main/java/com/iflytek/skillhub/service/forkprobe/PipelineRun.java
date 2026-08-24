package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.dto.forkprobe.OutputFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory state for a single pipeline (编排) run.
 * <p>
 * Lives in {@link ForkprobeComparisonService#pipelines} with a 30-minute TTL.
 * Not persisted to database — restarting the server clears in-flight pipelines.
 * <p>
 * A pipeline is three fixed, independent {@link Lane}s that run in parallel. Each
 * lane is itself a linear serial chain of {@link Stage}s: stage 0 runs first, its
 * output is handed off to stage 1, and so on. A failure inside one lane skips the
 * rest of that lane but never affects the other two. Every stage carries its own
 * verdict state.
 */
class PipelineRun {

    enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    enum StageStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    /**
     * One parallel lane. Mutable: the executor writes results into the lane and its
     * stages as it runs. Package-private so {@link ForkprobeComparisonService} can
     * read and write directly.
     */
    static final class Lane {
        final int index;
        final List<Stage> stages;
        volatile StageStatus status = StageStatus.PENDING;

        Lane(int index, List<Stage> stages) {
            this.index = index;
            this.stages = List.copyOf(stages);
        }
    }

    /**
     * A single stage of a lane's serial chain. Mutable: the executor writes results
     * into the stage as it runs. {@link #index} is 0-based within its lane.
     */
    static final class Stage {
        final int index;
        final ComparisonRun.SkillSpec spec;

        volatile StageStatus status = StageStatus.PENDING;
        volatile String inputPreview;
        volatile String output;
        volatile int tokensUsed;
        volatile float latencySeconds;
        volatile Boolean skillApplied;   // null = not verified
        volatile String appliedReason;
        volatile String error;
        volatile List<OutputFile> files = List.of();

        Stage(int index, ComparisonRun.SkillSpec spec) {
            this.index = index;
            this.spec = spec;
        }
    }

    private final String pipelineId;
    private final String userId;
    private final String providerId;
    private volatile Status status;
    private volatile boolean cancelled;
    private final String taskDescription;
    private final LlmTarget target;
    private final List<Lane> lanes;
    private final boolean autopilot;
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String error;

    PipelineRun(String userId, String providerId, String taskDescription, LlmTarget target, List<Lane> lanes) {
        this(userId, providerId, taskDescription, target, lanes, false);
    }

    /**
     * Full constructor. {@code autopilot=true} marks an AI-orchestrated lane chain so
     * stage hand-offs can carry the whole prior output instead of a tight tail.
     */
    PipelineRun(String userId, String providerId, String taskDescription, LlmTarget target,
                List<Lane> lanes, boolean autopilot) {
        this.pipelineId = UUID.randomUUID().toString();
        this.userId = userId;
        this.providerId = providerId;
        this.status = Status.PENDING;
        this.taskDescription = taskDescription;
        this.target = target;
        this.lanes = List.copyOf(lanes);
        this.autopilot = autopilot;
        this.createdAt = Instant.now();
    }

    String getUserId() {
        return userId;
    }

    String getProviderId() {
        return providerId;
    }

    String getPipelineId() {
        return pipelineId;
    }

    Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        // If a cancel landed while a lane was finishing, a COMPLETED must not
        // clobber the user-requested cancellation.
        if (this.cancelled && status == Status.COMPLETED) {
            status = Status.CANCELLED;
        }
        this.status = status;
        if (status == Status.RUNNING && this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        if (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        this.cancelled = true;
        if (this.status == Status.PENDING || this.status == Status.RUNNING) {
            setStatus(Status.CANCELLED);
        }
    }

    String getTaskDescription() {
        return taskDescription;
    }

    LlmTarget getTarget() {
        return target;
    }

    List<Lane> getLanes() {
        return lanes;
    }

    boolean isAutopilot() {
        return autopilot;
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
}
