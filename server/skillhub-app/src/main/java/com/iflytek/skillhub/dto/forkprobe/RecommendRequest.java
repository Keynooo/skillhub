package com.iflytek.skillhub.dto.forkprobe;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the forkprobe skill recommendation endpoint.
 */
public record RecommendRequest(
        @NotBlank(message = "任务描述不能为空")
        String taskDescription,

        /** Maximum number of candidates to return (default 5). */
        int maxCandidates
) {
    public RecommendRequest {
        if (maxCandidates <= 0) {
            maxCandidates = 5;
        }
    }
}
