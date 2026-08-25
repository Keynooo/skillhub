package com.iflytek.skillhub.service.forkprobe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * needsNetwork detection: retrieval-style skills (agent-reach) must be flagged
 * so the UI can badge them 需联网; pure reasoning skills must not be.
 */
class ForkprobeNeedsNetworkTest {

    @Test
    void flagsRetrievalSkill() {
        String agentReach = "# Agent Reach — 互联网能力路由器\n"
                + "全网调研类任务：组合多平台（Exa 搜索 + Twitter/Reddit 看讨论）\n"
                + "本 skill 只负责从互联网获取内容";
        assertTrue(ForkprobeComparisonService.looksNetworkBound(agentReach));
    }

    @Test
    void doesNotFlagReasoningSkill() {
        String decisionMatrix = "# 决策矩阵\n"
                + "用加权 Pugh 矩阵比较候选方案，列出准则、权重、打分、敏感性分析与偏误检查。";
        assertFalse(ForkprobeComparisonService.looksNetworkBound(decisionMatrix));
    }

    @Test
    void nullSafe() {
        assertFalse(ForkprobeComparisonService.looksNetworkBound(null));
    }
}
