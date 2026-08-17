package com.iflytek.skillhub.dto;

import java.time.Instant;

/**
 * Read model for one pending {@code label_tagging_review} row shown to an admin.
 */
public record LabelTaggingReviewResponse(
        Long skillId,
        Long namespaceId,
        String skillName,
        String skillSlug,
        String reason,
        Instant createdAt
) {}
