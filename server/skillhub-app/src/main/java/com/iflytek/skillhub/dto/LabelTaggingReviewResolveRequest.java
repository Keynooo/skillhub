package com.iflytek.skillhub.dto;

import java.util.List;

/**
 * Body for resolving a pending label-tagging review. {@code labels} is optional: when provided the
 * slugs are attached to the skill before the review is resolved; when omitted the review is simply
 * dismissed (the skill stays untagged).
 */
public record LabelTaggingReviewResolveRequest(
        List<String> labels
) {}
