package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.dto.forkprobe.RecommendedSkill;
import com.iflytek.skillhub.service.SkillSearchAppService.RecommendCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for the LLM recommendation prompt/parse layer (no network).
 * These lock the cross-language contract: the prompt must carry the Chinese summary so a
 * Chinese query can match an English skill, and the parser must map integer indices back
 * onto the candidate pool safely.
 */
class ForkprobeRecommendationTest {

    private static RecommendCandidate c(String slug, String name, String summary, String summaryZh) {
        return new RecommendCandidate(1L, slug, name, summary, summaryZh, "global");
    }

    @Test
    void promptCarriesBilingualSummarySoChineseQueryCanMatchEnglishSkill() {
        List<RecommendCandidate> pool = List.of(
                c("weather", "weather", "Current weather and forecasts.", "当前天气和预报"),
                c("diagram-maker", "Diagram Maker", "Create SVG diagrams.", "创建 SVG 图表"));

        String prompt = ForkprobeComparisonService.buildRecommendationPrompt("帮我查天气", pool, 5);

        assertTrue(prompt.contains("帮我查天气"), "prompt must embed the task");
        assertTrue(prompt.contains("[1] global/weather — weather: Current weather and forecasts."),
                "prompt must list candidate #1 with EN summary");
        assertTrue(prompt.contains("当前天气和预报"),
                "prompt must carry the Chinese summary — the cross-language bridge");
        assertTrue(prompt.contains("[2] global/diagram-maker"), "prompt must list candidate #2");
        assertTrue(prompt.contains("共 2 个技能"), "prompt must state pool size");
    }

    @Test
    void parseKeepsRankedOrderReturnedByModel() {
        List<RecommendCandidate> pool = List.of(
                c("weather", "weather", "w", null),
                c("diagram-maker", "Diagram Maker", "d", null));

        String json = "{\"ranked\":[{\"index\":2,\"reason\":\"画图\"},{\"index\":1,\"reason\":\"查天气\"}]}";
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(json, pool, 5);

        assertEquals(2, result.size());
        assertEquals("global/diagram-maker", result.get(0).coordinate());
        assertEquals("global/weather", result.get(1).coordinate());
        assertEquals("查天气", result.get(1).reasonZh());
    }

    @Test
    void parseDropsOutOfRangeIndices() {
        List<RecommendCandidate> pool = List.of(c("weather", "weather", "w", null));
        String json = "{\"ranked\":[{\"index\":99,\"reason\":\"x\"},{\"index\":1,\"reason\":\"y\"}]}";
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(json, pool, 5);
        assertEquals(1, result.size());
        assertEquals("global/weather", result.get(0).coordinate());
    }

    @Test
    void parseDeduplicatesRepeatedIndices() {
        List<RecommendCandidate> pool = List.of(
                c("weather", "weather", "w", null),
                c("diagram-maker", "Diagram Maker", "d", null));
        String json = "{\"ranked\":[{\"index\":1,\"reason\":\"a\"},{\"index\":1,\"reason\":\"b\"}]}";
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(json, pool, 5);
        assertEquals(1, result.size());
    }

    @Test
    void parseToleratesMarkdownFences() {
        List<RecommendCandidate> pool = List.of(c("weather", "weather", "w", null));
        String json = "```json\n{\"ranked\":[{\"index\":1,\"reason\":\"查天气\"}]}\n```";
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(json, pool, 5);
        assertEquals(1, result.size());
        assertEquals("global/weather", result.get(0).coordinate());
    }

    @Test
    void parseReturnsEmptyForEmptyRanked() {
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(
                "{\"ranked\":[]}", List.of(c("weather", "weather", "w", null)), 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseReturnsEmptyForInvalidJson() {
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(
                "not json at all", List.of(c("weather", "weather", "w", null)), 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseRespectsMaxCandidatesCap() {
        List<RecommendCandidate> pool = List.of(
                c("a", "a", "a", null), c("b", "b", "b", null), c("c", "c", "c", null));
        String json = "{\"ranked\":[{\"index\":1},{\"index\":2},{\"index\":3}]}";
        List<RecommendedSkill> result = ForkprobeComparisonService.parseRecommendation(json, pool, 2);
        assertEquals(2, result.size());
    }

    @Test
    void truncateSummaryCapsAndEllipsizes() {
        assertEquals("", ForkprobeComparisonService.truncateSummary(null, 10));
        assertEquals("short", ForkprobeComparisonService.truncateSummary("short", 10));
        assertEquals("0123456789…", ForkprobeComparisonService.truncateSummary("0123456789abcdef", 10));
    }

    @Test
    void metaSkillsAreExcludedFromRecommendationPool() {
        assertTrue(ForkprobeComparisonService.isRecommendationEligible("weather"));
        assertTrue(ForkprobeComparisonService.isRecommendationEligible(null));
        assertFalse(ForkprobeComparisonService.isRecommendationEligible("forkprobe"));
        assertFalse(ForkprobeComparisonService.isRecommendationEligible("find-skills"));
        assertFalse(ForkprobeComparisonService.isRecommendationEligible("skillhub-hello"));
    }
}
