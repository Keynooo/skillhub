package com.iflytek.skillhub.domain.event;

/**
 * Published when a skill's LLM auto-tagging produced no valid scenario labels, signaling that
 * a human needs to label it manually.
 */
public record LabelTaggingFailedEvent(Long skillId) {
}
