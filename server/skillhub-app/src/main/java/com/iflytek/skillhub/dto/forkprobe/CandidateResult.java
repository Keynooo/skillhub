package com.iflytek.skillhub.dto.forkprobe;

/**
 * Result for a single candidate skill within a comparison run.
 */
public record CandidateResult(
        String skillCoordinate,
        String skillName,
        String output,
        int tokensUsed,
        float latencySeconds,

        /** null = not verified yet, true = ✅ skill applied, false = ⚠️ skill not applied */
        Boolean skillApplied,
        String appliedReason,
        String error
) {}
