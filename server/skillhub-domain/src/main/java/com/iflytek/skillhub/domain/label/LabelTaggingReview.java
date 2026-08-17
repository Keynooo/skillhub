package com.iflytek.skillhub.domain.label;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;

/**
 * A durable reminder that a skill's LLM auto-tagging produced no valid scenario labels and
 * therefore needs a human to label it manually. One row per skill ({@code skill_id} is unique).
 */
@Entity
@Table(name = "label_tagging_review")
public class LabelTaggingReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    @Column(name = "namespace_id")
    private Long namespaceId;

    @Column(name = "skill_name", length = 256)
    private String skillName;

    @Column(name = "skill_slug", length = 256)
    private String skillSlug;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LabelTaggingReviewStatus status = LabelTaggingReviewStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 128)
    private String resolvedBy;

    protected LabelTaggingReview() {
    }

    public LabelTaggingReview(Long skillId, Long namespaceId, String skillName, String skillSlug, String reason) {
        this.skillId = skillId;
        this.namespaceId = namespaceId;
        this.skillName = skillName;
        this.skillSlug = skillSlug;
        this.reason = reason;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public Long getSkillId() {
        return skillId;
    }

    public Long getNamespaceId() {
        return namespaceId;
    }

    public String getSkillName() {
        return skillName;
    }

    public String getSkillSlug() {
        return skillSlug;
    }

    public String getReason() {
        return reason;
    }

    public LabelTaggingReviewStatus getStatus() {
        return status;
    }

    public void setStatus(LabelTaggingReviewStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
