package com.iflytek.skillhub.domain.label;

/**
 * Lifecycle of a {@link LabelTaggingReview}: a skill that failed LLM auto-tagging starts
 * {@link #PENDING} (awaiting manual labeling) and moves to {@link #RESOLVED} once a human
 * labels it or dismisses it.
 */
public enum LabelTaggingReviewStatus {
    PENDING,
    RESOLVED
}
