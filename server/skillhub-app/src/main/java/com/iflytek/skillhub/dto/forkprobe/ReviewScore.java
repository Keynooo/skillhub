package com.iflytek.skillhub.dto.forkprobe;

/**
 * A single scoring dimension produced by the AI review judge (e.g. 输出质量 / 相关性 / 可用性).
 */
public record ReviewScore(
        String label,
        int score
) {}
