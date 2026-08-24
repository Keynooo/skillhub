package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.domain.forkprobe.ForkprobeComparisonRepository;
import com.iflytek.skillhub.domain.forkprobe.ForkprobePipelineRepository;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.forkprobe.RecommendedSkill;
import com.iflytek.skillhub.service.AnthropicService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import com.iflytek.skillhub.service.SkillSearchAppService.RecommendCandidate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Live end-to-end proof that the recommendation re-rank surfaces the right skill for a
 * Chinese query — the acceptance gate "帮我查天气 → weather". Reads the REAL built-in
 * skills off disk and calls the REAL GLM endpoint (matches the main server's
 * no-default-key scenario), so the assertion exercises the full cross-language path.
 *
 * <p>Skipped in CI unless {@code GLM_API_KEY} is set. Run locally with:
 * <pre>
 *   GLM_API_KEY=... ./mvnw -pl skillhub-app -am test -Dtest=ForkprobeRecommendationLiveTest
 * </pre>
 */
class ForkprobeRecommendationLiveTest {

    private static final Pattern FRONTMATTER_FIELD = Pattern.compile("(?m)^(\\w+)\\s*:\\s*(.+)$");

    @Test
    void recommendSurfacesExpectedSkillForEachUseCase() throws Exception {
        String glmKey = System.getenv("GLM_API_KEY");
        assumeTrue(glmKey != null && !glmKey.isBlank(),
                "GLM_API_KEY not set — skipping live test");

        List<RecommendCandidate> pool = loadBuiltinPool();
        assertFalse(pool.isEmpty(), "built-in skill pool must not be empty");
        ForkprobeComparisonService svc = buildService(pool, glmKey);

        // Use-case matrix: Chinese input → expected built-in skill.
        String[][] cases = {
                {"帮我查一下北京今天的天气", "weather"},
                {"帮我画一个系统架构图", "diagram-maker"},
                {"帮我从一段视频里提取关键帧", "video-frames"},
                {"帮我记录并总结今天的会议纪要", "meeting-note-summarizer"},
                {"帮我写一个睡前故事", "storytelling-advisor"},
                {"帮我从三台笔记本电脑里选一台：MacBook Air M3（¥7999）、联想小新 Pro 16（¥5999）、华为 MateBook X Pro（¥9999）。主要用于写代码、偶尔剪视频，预算 8000 以内，看重续航和屏幕。", "decision-matrix"},
        };

        for (String[] tc : cases) {
            List<RecommendedSkill> r = svc.recommend(tc[0], 5, null, Map.of());
            print(tc[0], tc[1], r);

            List<String> coords = r.stream().skip(1).map(RecommendedSkill::coordinate).toList();
            assertTrue(coords.stream().anyMatch(s -> s.endsWith("/" + tc[1])),
                    "用例 '" + tc[0] + "' 未命中期望技能 " + tc[1] + "，实际: " + coords);
        }
    }

    @Test
    void weatherQueryPutsWeatherSkillFirst() throws Exception {
        String glmKey = System.getenv("GLM_API_KEY");
        assumeTrue(glmKey != null && !glmKey.isBlank(), "GLM_API_KEY not set — skipping live test");

        List<RecommendCandidate> pool = loadBuiltinPool();
        ForkprobeComparisonService svc = buildService(pool, glmKey);

        List<RecommendedSkill> r = svc.recommend("帮我查天气", 5, null, Map.of());
        print("帮我查天气", "weather", r);

        assertTrue(r.size() > 1, "expected at least the baseline + one LLM recommendation");
        assertTrue(r.get(1).coordinate().endsWith("/weather"),
                "天气查询的第一个推荐应为 weather，实际: " + r.get(1).coordinate());
    }

    // --- helpers ---

    private static void print(String query, String expected, List<RecommendedSkill> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 用例: ").append(query).append(" (期望: ").append(expected).append(") ===\n");
        for (int i = 0; i < r.size(); i++) {
            RecommendedSkill s = r.get(i);
            sb.append("  [").append(i).append("] ").append(s.coordinate())
                    .append(" — ").append(s.name())
                    .append(" (").append(s.reasonZh()).append(")\n");
        }
        System.out.println(sb);
    }

    private static List<RecommendCandidate> loadBuiltinPool() {
        Path skillsDir = resolveSkillsDir();
        if (skillsDir == null) {
            return List.of();
        }
        List<RecommendCandidate> pool = new ArrayList<>();
        try (var stream = Files.list(skillsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                RecommendCandidate candidate = readCandidate(dir);
                if (candidate != null) {
                    pool.add(candidate);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return pool;
    }

    private static RecommendCandidate readCandidate(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return null;
        }
        try {
            String text = Files.readString(skillMd, StandardCharsets.UTF_8);
            String name = extractField(text, "name");
            String description = extractField(text, "description");
            if (name == null || name.isBlank()) {
                name = skillDir.getFileName().toString();
            }
            return new RecommendCandidate(
                    0L, skillDir.getFileName().toString(), name, description, null, "builtin");
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractField(String text, String field) {
        Matcher m = FRONTMATTER_FIELD.matcher(text);
        while (m.find()) {
            if (field.equals(m.group(1))) {
                return m.group(2).trim();
            }
        }
        return null;
    }

    private static Path resolveSkillsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("builtin-skills").resolve("skills");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static ForkprobeComparisonService buildService(List<RecommendCandidate> pool, String glmKey) {
        String glmUrl = envOr("GLM_BASE_URL", "https://open.bigmodel.cn/api/anthropic");
        String glmModel = envOr("GLM_MODEL", "glm-5.2");

        AnthropicProperties props = new AnthropicProperties();
        // No default API key — forces the glm provider branch, mirroring the main server.
        props.setApiKey(null);
        AnthropicProperties.Provider glm = new AnthropicProperties.Provider();
        glm.setBaseUrl(glmUrl);
        glm.setApiKey(glmKey);
        glm.setModel(glmModel);
        props.getProviders().put("glm", glm);

        AnthropicService anthropic = new AnthropicService(props);
        SkillQueryService skillQueryService = mock(SkillQueryService.class);
        SkillSearchAppService search = mock(SkillSearchAppService.class);
        when(search.listRecommendCandidates(any(), any())).thenReturn(pool);
        ForkprobeExecutorProperties execProps = mock(ForkprobeExecutorProperties.class);
        ForkprobeComparisonRepository repo = mock(ForkprobeComparisonRepository.class);
        ForkprobePipelineRepository pipelineRepo = mock(ForkprobePipelineRepository.class);
        Semaphore sem = new Semaphore(2);

        return new ForkprobeComparisonService(
                skillQueryService, search, anthropic, props, execProps, sem, repo, pipelineRepo,
                3, 5, 30, 4096);
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
