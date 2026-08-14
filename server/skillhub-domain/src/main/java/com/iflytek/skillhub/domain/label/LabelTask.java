package com.iflytek.skillhub.domain.label;

import java.util.Map;

/**
 * A queued request to auto-tag a skill via the LLM labeling pipeline.
 *
 * <p>Mirrors {@link com.iflytek.skillhub.domain.security.ScanTask}: the domain only publishes the
 * task onto a Redis stream; an application-level consumer performs the actual LLM call and persists
 * the resulting labels. Keeping the LLM call out of the domain avoids a dependency on the app layer.
 */
public record LabelTask(
        String taskId,
        Long skillId,
        String skillName,
        String summary,
        String bodySample,
        String operatorId,
        long createdAtMillis,
        Map<String, String> metadata
) {
}
