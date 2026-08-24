package com.iflytek.skillhub.domain.forkprobe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persisted record of a completed forkprobe pipeline (编排) run.
 * <p>
 * Written only when a run reaches a terminal state ({@code COMPLETED} /
 * {@code FAILED} / {@code CANCELLED}), so the user can revisit past compositions
 * after the in-memory pipeline store's TTL has expired. The lane results are
 * serialized as a JSON array of {@code PipelineLaneResult} objects into
 * {@link #lanesJson}.
 */
@Entity
@Table(name = "forkprobe_pipeline")
public class ForkprobePipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false, unique = true, length = 64)
    private String pipelineId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "task_description", nullable = false, columnDefinition = "TEXT")
    private String taskDescription;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lanes_json", columnDefinition = "jsonb")
    private String lanesJson;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ForkprobePipeline() {
    }

    public ForkprobePipeline(String pipelineId,
                             String userId,
                             String taskDescription,
                             String provider,
                             String status,
                             String lanesJson,
                             String error,
                             Instant createdAt,
                             Instant startedAt,
                             Instant completedAt) {
        this.pipelineId = pipelineId;
        this.userId = userId;
        this.taskDescription = taskDescription;
        this.provider = provider;
        this.status = status;
        this.lanesJson = lanesJson;
        this.error = error;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public String getProvider() {
        return provider;
    }

    public String getStatus() {
        return status;
    }

    public String getLanesJson() {
        return lanesJson;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
