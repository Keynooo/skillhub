package com.iflytek.skillhub.dto.forkprobe;

import java.util.List;

/**
 * Response for the skill recommendation endpoint.
 */
public record RecommendResponse(
        List<RecommendedSkill> candidates
) {}
