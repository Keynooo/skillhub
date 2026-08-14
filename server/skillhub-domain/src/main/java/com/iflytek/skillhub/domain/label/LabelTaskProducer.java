package com.iflytek.skillhub.domain.label;

/**
 * Publishes {@link LabelTask}s onto the auto-tagging work stream.
 *
 * <p>A no-op implementation is wired when {@code skillhub.label.auto-tagging.enabled} is false, so
 * callers can publish unconditionally without a feature flag.
 */
public interface LabelTaskProducer {
    void publishLabelTask(LabelTask task);
}
