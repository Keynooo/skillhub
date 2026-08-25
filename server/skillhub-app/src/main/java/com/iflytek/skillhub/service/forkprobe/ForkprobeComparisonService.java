package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.domain.forkprobe.ForkprobeComparison;
import com.iflytek.skillhub.domain.forkprobe.ForkprobeComparisonRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.dto.forkprobe.CandidateResult;
import com.iflytek.skillhub.dto.forkprobe.CompareResponse;
import com.iflytek.skillhub.dto.forkprobe.ComparisonHistoryItem;
import com.iflytek.skillhub.dto.forkprobe.ComparisonStatusResponse;
import com.iflytek.skillhub.dto.forkprobe.OutputFile;
import com.iflytek.skillhub.dto.forkprobe.RecommendedSkill;
import com.iflytek.skillhub.dto.forkprobe.ReviewResult;
import com.iflytek.skillhub.dto.forkprobe.ReviewScore;
import com.iflytek.skillhub.dto.forkprobe.SkillReview;
import com.iflytek.skillhub.service.AnthropicService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Core orchestration service for the forkprobe skill comparison sandbox.
 * <p>
 * Manages the lifecycle of comparison runs: recommendation, execution, and
 * status polling. Comparison state is stored in-memory with a configurable
 * TTL (default 30 minutes).
 */
@Service
public class ForkprobeComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ForkprobeComparisonService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * System prompt for the independent AI review judge. It is asked to return
     * strict JSON only so the result can be parsed deterministically.
     */
    private static final String REVIEW_SYSTEM_PROMPT =
            "You are an independent skill evaluation judge. You review multiple candidate outputs " +
            "produced for the SAME task and recommend the single best one. Judge only the provided " +
            "outputs — do not introduce outside information or assume unstated facts.\n\n" +
            "Respond with STRICT JSON only (no markdown fences, no commentary). Schema:\n" +
            "{\n" +
            "  \"winner\": \"<coordinate of the best candidate>\",\n" +
            "  \"reason\": \"<one-sentence recommendation reason>\",\n" +
            "  \"scores\": [\n" +
            "    {\n" +
            "      \"coordinate\": \"<candidate coordinate>\",\n" +
            "      \"overall\": 0,\n" +
            "      \"dimensions\": [\n" +
            "        {\"label\": \"输出质量\", \"score\": 0},\n" +
            "        {\"label\": \"相关性\", \"score\": 0},\n" +
            "        {\"label\": \"可用性\", \"score\": 0}\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "Each score is an integer 0-100. The \"winner\" and every \"coordinate\" must be the exact " +
            "coordinate string shown for that candidate (the text between the \"[N] \" prefix and the " +
            "\" — \" separator), NOT the \"[N]\" label or the display name. Include exactly one \"scores\" " +
            "entry per candidate, in the same order provided.";

    /**
     * System prompt for the skill recommendation engine. Returns strict JSON whose
     * entries reference the catalog by integer {@code index}, so no hallucinated
     * coordinate string can slip through — out-of-range indices are dropped.
     */
    private static final String RECOMMEND_SYSTEM_PROMPT =
            "You are a skill recommendation engine. Given a user's task description (which may be " +
            "in Chinese or English) and a numbered catalog of available skills, select the skills " +
            "whose purpose best matches the task, regardless of language. Do not introduce skills " +
            "that are not in the catalog.\n\n" +
            "Respond with STRICT JSON only (no markdown fences, no commentary). Schema:\n" +
            "{\n" +
            "  \"ranked\": [\n" +
            "    {\"index\": <int, the [N] number from the catalog>,\n" +
            "     \"reason\": \"<a VERY SHORT Chinese phrase, at most 10 characters, that names the match, e.g. 查天气 / 画流程图 / 整理会议纪要>\"}\n" +
            "  ]\n" +
            "}\n" +
            "Order by relevance descending. Use only index values present in the catalog. If nothing " +
            "fits, return {\"ranked\": []}.";

    /**
     * Meta-skills that describe SkillHub/forkprobe itself rather than a task the user
     * wants to accomplish. Recommending them on the forkprobe comparison page is
     * self-referential (comparing the comparison feature against itself), so they are
     * dropped from the recommendation candidate pool.
     */
    private static final Set<String> RECOMMENDATION_EXCLUDED_SLUGS =
            Set.of("forkprobe", "find-skills", "skillhub-hello");

    private final ConcurrentHashMap<String, ComparisonRun> comparisons = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    private final SkillQueryService skillQueryService;
    private final SkillSearchAppService skillSearchAppService;
    private final AnthropicService anthropicService;
    private final AnthropicProperties anthropicProperties;
    private final SkillExecutor skillExecutor;
    private final ExecutorService comparisonExecutor;
    private final ForkprobeComparisonRepository comparisonRepository;

    private final int maxSkills;
    private final int maxSkillsCap;
    private final int comparisonTtlMinutes;
    private final int reviewMaxTokens;

    /** Kill switch for the LLM-based recommendation re-rank (falls back to lexical hash). */
    @Value("${skillhub.forkprobe.recommendation.enabled:true}")
    private boolean recommendationEnabled = true;

    /** Provider pin for recommendation: {@code default} = auto-resolve (default key, else glm/local). */
    @Value("${skillhub.forkprobe.recommendation.provider:default}")
    private String recommendationProvider = "default";

    public ForkprobeComparisonService(
            SkillQueryService skillQueryService,
            SkillSearchAppService skillSearchAppService,
            AnthropicService anthropicService,
            AnthropicProperties anthropicProperties,
            ForkprobeExecutorProperties executorProperties,
            Semaphore sandboxSemaphore,
            ForkprobeComparisonRepository comparisonRepository,
            @Value("${skillhub.forkprobe.max-skills:3}") int maxSkills,
            @Value("${skillhub.forkprobe.max-skills-cap:5}") int maxSkillsCap,
            @Value("${skillhub.forkprobe.comparison-ttl-minutes:30}") int comparisonTtlMinutes,
            @Value("${skillhub.forkprobe.review-max-tokens:4096}") int reviewMaxTokens) {
        this.skillQueryService = skillQueryService;
        this.skillSearchAppService = skillSearchAppService;
        this.anthropicService = anthropicService;
        this.anthropicProperties = anthropicProperties;
        this.maxSkills = maxSkills;
        this.maxSkillsCap = maxSkillsCap;
        this.comparisonTtlMinutes = comparisonTtlMinutes;
        this.reviewMaxTokens = reviewMaxTokens;
        this.comparisonRepository = comparisonRepository;
        this.skillExecutor = createSkillExecutor(
                anthropicService, anthropicProperties, executorProperties, sandboxSemaphore);
        this.comparisonExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Periodic cleanup of stale comparisons
        this.cleanupExecutor.scheduleAtFixedRate(
                this::cleanupStaleComparisons, 5, 5, TimeUnit.MINUTES);
    }

    private static SkillExecutor createSkillExecutor(
            AnthropicService anthropicService,
            AnthropicProperties anthropicProperties,
            ForkprobeExecutorProperties executorProperties,
            Semaphore sandboxSemaphore) {
        String mode = executorProperties.getMode();
        if ("claude-cli".equalsIgnoreCase(mode)) {
            return new ClaudeCodeSubprocessExecutor(anthropicService, executorProperties);
        }
        if ("docker".equalsIgnoreCase(mode)) {
            return new DockerSandboxSkillExecutor(
                    anthropicService, anthropicProperties, executorProperties, sandboxSemaphore);
        }
        return new DirectApiSkillExecutor(anthropicService, anthropicProperties.getMaxTokens());
    }

    // --- Public API ---

    /**
     * Recommend skills for a given task description.
     * <p>
     * Prefers an LLM re-rank over the full visible pool — the only mechanism with
     * cross-language recall (e.g. "帮我查天气" → the English "weather" skill) that the
     * lexical-hash fallback cannot provide. On any LLM failure it degrades to the
     * deterministic lexical-hash ranking, so it is never worse than before.
     */
    public List<RecommendedSkill> recommend(String taskDescription, int maxCandidates,
                                            String userId, Map<Long, NamespaceRole> userNsRoles) {
        List<RecommendedSkill> candidates = new ArrayList<>();
        // Baseline always first
        candidates.add(baselineSkill());

        if (recommendationEnabled) {
            List<RecommendedSkill> llm = recommendViaLlm(taskDescription, maxCandidates, userId, userNsRoles);
            if (!llm.isEmpty()) {
                candidates.addAll(llm);
                enrichNetworkFlags(candidates, userId, userNsRoles);
                return candidates;
            }
            log.warn("LLM recommendation produced no candidates; falling back to lexical-hash ranking");
        }

        try {
            // Fallback: deterministic lexical-hash recall over the platform's published
            // skills. Kept as-is so an unconfigured/overloaded LLM is never a regression.
            List<SkillSummaryResponse> matched =
                    skillSearchAppService.semanticSearch(taskDescription, maxCandidates, userId, userNsRoles);

            for (SkillSummaryResponse skill : matched) {
                if (candidates.size() >= maxCandidates + 1) break; // +1 for baseline
                candidates.add(toRecommendedSkill(skill));
            }
        } catch (Exception e) {
            log.warn("Platform skill recommendation failed: {}", e.getMessage());
        }

        enrichNetworkFlags(candidates, userId, userNsRoles);
        return candidates;
    }

    /**
     * Flag candidates whose SKILL.md drives the agent to the live web (search/fetch/
     * crawl). The comparison sandbox runs on a direct bridge network with no proxy, so
     * these skills are prone to slow runs or timeouts — the UI badges them as 需联网.
     * Best-effort: any load failure leaves the flag false.
     */
    private void enrichNetworkFlags(List<RecommendedSkill> candidates, String userId,
                                    Map<Long, NamespaceRole> userNsRoles) {
        for (int i = 0; i < candidates.size(); i++) {
            RecommendedSkill c = candidates.get(i);
            if ("baseline".equals(c.coordinate()) || c.coordinate().startsWith("catalog:")) {
                continue;
            }
            String[] parts = c.coordinate().split("/", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                String skillMd = loadSkillHubPrompt(parts[0], parts[1], userId, userNsRoles);
                if (looksNetworkBound(skillMd)) {
                    candidates.set(i, c.withNeedsNetwork(true));
                }
            } catch (Exception e) {
                log.debug("needsNetwork probe failed for {}: {}", c.coordinate(), e.getMessage());
            }
        }
    }

    /** Strong live-web signals in a SKILL.md (calibrated to avoid over-flagging). */
    private static final java.util.regex.Pattern NETWORK_BOUND = java.util.regex.Pattern.compile(
            "联网|互联网|在线检索|在线搜索|搜索引擎|爬虫|websearch|webfetch|web search|web_search|web_fetch|jina|crawl",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean looksNetworkBound(String skillMd) {
        return skillMd != null && NETWORK_BOUND.matcher(skillMd).find();
    }

    /**
     * LLM re-rank over the full visible pool. Returns {@link List#of()} on any failure
     * (no pool, no target, call failure, unparseable response) so the caller falls back
     * to the lexical-hash ranking.
     */
    private List<RecommendedSkill> recommendViaLlm(String taskDescription, int maxCandidates,
                                                  String userId, Map<Long, NamespaceRole> userNsRoles) {
        try {
            List<SkillSearchAppService.RecommendCandidate> pool =
                    skillSearchAppService.listRecommendCandidates(userId, userNsRoles).stream()
                            .filter(c -> isRecommendationEligible(c.slug()))
                            .toList();
            if (pool.isEmpty()) {
                return List.of();
            }
            LlmTarget target = resolveRecommendationTarget();
            if (target == null) {
                log.info("No LLM target configured for recommendation; skipping LLM re-rank");
                return List.of();
            }
            String userMessage = buildRecommendationPrompt(taskDescription, pool, maxCandidates);
            String content = callRecommendationModel(userMessage, target);
            if (content == null || content.isBlank()) {
                return List.of();
            }
            return parseRecommendation(content, pool, maxCandidates);
        } catch (Exception e) {
            log.warn("LLM recommendation failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolve a concrete LLM target for recommendation, guaranteed to be a working
     * provider on both dev (default key present) and main (no default key, glm/local
     * only). Returns {@code null} when nothing is configured, which triggers the
     * lexical-hash fallback.
     */
    private LlmTarget resolveRecommendationTarget() {
        if (recommendationProvider != null && !recommendationProvider.isBlank()
                && !"default".equalsIgnoreCase(recommendationProvider)) {
            AnthropicProperties.Provider p = anthropicProperties.getProviders().get(recommendationProvider);
            if (p != null && p.isConfigured()) {
                return new LlmTarget(p.getBaseUrl(), p.getApiKey(), p.getModel());
            }
            log.warn("Recommendation provider '{}' not configured; falling back to auto-resolve",
                    recommendationProvider);
        }
        if (anthropicProperties.isApiKeyConfigured()) {
            String model = anthropicProperties.getJudgeModel() != null
                    && !anthropicProperties.getJudgeModel().isBlank()
                    ? anthropicProperties.getJudgeModel() : anthropicProperties.getModel();
            return new LlmTarget(anthropicProperties.getBaseUrl(), anthropicProperties.getApiKey(), model);
        }
        for (String id : List.of("glm", "local")) {
            AnthropicProperties.Provider p = anthropicProperties.getProviders().get(id);
            if (p != null && p.isConfigured()) {
                return new LlmTarget(p.getBaseUrl(), p.getApiKey(), p.getModel());
            }
        }
        return null;
    }

    static String buildRecommendationPrompt(String taskDescription,
                                            List<SkillSearchAppService.RecommendCandidate> pool,
                                            int maxCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(taskDescription).append("\n\n");
        sb.append("可用技能（[编号] 坐标 — 名称: 简介）：\n");
        for (int i = 0; i < pool.size(); i++) {
            SkillSearchAppService.RecommendCandidate c = pool.get(i);
            String coordinate = c.namespaceSlug() != null && !c.namespaceSlug().isBlank()
                    ? c.namespaceSlug() + "/" + c.slug() : c.slug();
            sb.append('[').append(i + 1).append("] ").append(coordinate)
                    .append(" — ").append(c.displayName()).append(": ")
                    .append(truncateSummary(c.summary(), 160));
            if (c.summaryZh() != null && !c.summaryZh().isBlank()) {
                sb.append(" / ").append(truncateSummary(c.summaryZh(), 160));
            }
            sb.append('\n');
        }
        sb.append("共 ").append(pool.size()).append(" 个技能。请返回最相关的 ")
                .append(Math.max(1, maxCandidates)).append(" 个。");
        return sb.toString();
    }

    /**
     * Call the recommendation model with retry on blank content, mirroring the review
     * judge's blank-guard. Returns {@code null} when all attempts fail or are blank.
     */
    private String callRecommendationModel(String userMessage, LlmTarget target) {
        int maxTokens = 1024;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                AnthropicService.AnthropicMessageResponse response = anthropicService.sendMessageWithRetry(
                        RECOMMEND_SYSTEM_PROMPT, userMessage, maxTokens, 0,
                        target.model(), target.baseUrl(), target.apiKey());
                if (response.content() != null && !response.content().isBlank()) {
                    return response.content();
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Recommendation LLM call failed (attempt {}): {}", attempt + 1, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Parse the recommendation JSON into ordered {@link RecommendedSkill} entries.
     * Tolerant of leading prose / markdown fences (outermost {@code {...}}), and maps
     * integer indices straight onto the pool — out-of-range indices are dropped.
     */
    static List<RecommendedSkill> parseRecommendation(String content,
                                                      List<SkillSearchAppService.RecommendCandidate> pool,
                                                      int maxCandidates) {
        try {
            String json = content.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end < start) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
            JsonNode ranked = root.path("ranked");
            if (!ranked.isArray()) {
                return List.of();
            }
            Map<Integer, RecommendedSkill> byIndex = new LinkedHashMap<>();
            for (JsonNode entry : ranked) {
                int index = entry.path("index").asInt(-1);
                if (index < 1 || index > pool.size() || byIndex.containsKey(index)) {
                    continue;
                }
                String reason = entry.path("reason").asText("");
                byIndex.put(index, toRecommendedSkill(pool.get(index - 1), reason));
                if (byIndex.size() >= maxCandidates) {
                    break;
                }
            }
            return List.copyOf(byIndex.values());
        } catch (Exception e) {
            log.warn("Failed to parse recommendation response: {}", e.getMessage());
            return List.of();
        }
    }

    static RecommendedSkill toRecommendedSkill(SkillSearchAppService.RecommendCandidate c, String reason) {
        String namespace = c.namespaceSlug() != null ? c.namespaceSlug() : "";
        String name = c.displayName() != null && !c.displayName().isBlank() ? c.displayName() : c.slug();
        String coordinate = namespace.isBlank() ? c.slug() : namespace + "/" + c.slug();
        String reasonZh = reason != null && !reason.isBlank()
                ? truncateSummary(reason, 20)
                : (c.summary() != null && !c.summary().isBlank() ? c.summary() : "平台技能，可直接加入对比");
        return new RecommendedSkill(coordinate, name, namespace, reasonZh, "skillhub", "skillhub", 0, null, false);
    }

    static String truncateSummary(String text, int maxChars) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= maxChars ? t : t.substring(0, maxChars) + "…";
    }

    /**
     * Whether a skill slug is eligible for the forkprobe recommendation pool. Meta-skills
     * that describe the tool itself ({@link #RECOMMENDATION_EXCLUDED_SLUGS}) are never a
     * useful "skill to compare for this task", so they are dropped. Package-private for
     * testability.
     */
    static boolean isRecommendationEligible(String slug) {
        return slug == null || !RECOMMENDATION_EXCLUDED_SLUGS.contains(slug);
    }

    private RecommendedSkill toRecommendedSkill(SkillSummaryResponse skill) {
        String namespace = skill.namespace() != null ? skill.namespace() : "";
        String name = skill.displayName() != null && !skill.displayName().isBlank()
                ? skill.displayName() : skill.slug();
        String reasonZh = skill.summary() != null && !skill.summary().isBlank()
                ? skill.summary() : "平台技能，可直接加入对比";
        int stars = skill.starCount() != null ? skill.starCount() : 0;
        return new RecommendedSkill(
                coordinateOf(skill), name, namespace, reasonZh, "skillhub", "skillhub", stars, null, false);
    }

    private String coordinateOf(SkillSummaryResponse skill) {
        String namespace = skill.namespace() != null ? skill.namespace() : "";
        return namespace.isBlank() ? skill.slug() : namespace + "/" + skill.slug();
    }

    /**
     * Start a new comparison run.
     */
    public CompareResponse startComparison(String userId, String taskDescription, List<String> skillCoordinates,
                                           String provider, Map<Long, NamespaceRole> userNsRoles) {
        // Validate
        if (skillCoordinates.size() > maxSkillsCap) {
            throw new IllegalArgumentException("最多只能选择 " + maxSkillsCap + " 个 skill");
        }

        // Resolve skill specs, visibility-scoped to the requesting user
        List<ComparisonRun.SkillSpec> specs = resolveSkillSpecs(skillCoordinates, userId, userNsRoles);
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("没有找到任何可执行的 skill");
        }

        // Create run
        ComparisonRun run = new ComparisonRun(
                userId, normalizeProvider(provider), taskDescription, resolveTarget(provider), specs);
        comparisons.put(run.getComparisonId(), run);

        // Execute asynchronously — submit to executor directly (not @Async,
        // which wouldn't work for internal calls due to AOP proxy limitations)
        comparisonExecutor.submit(() -> executeComparison(run.getComparisonId()));

        return new CompareResponse(
                run.getComparisonId(),
                run.getStatus().name(),
                run.getCreatedAt().atOffset(ZoneOffset.UTC).toString()
        );
    }

    /**
     * Cancel a running/pending comparison. In-flight skill executions are signalled to
     * stop (subprocess/container executors destroy their process); the run is marked
     * {@code CANCELLED} immediately so the polling frontend stops.
     *
     * @return {@code false} if the comparison no longer exists
     */
    public boolean cancelComparison(String comparisonId) {
        ComparisonRun run = comparisons.get(comparisonId);
        if (run == null) {
            return false;
        }
        run.cancel();
        log.info("Comparison {} cancelled", comparisonId);
        return true;
    }

    /**
     * Get the current status of a comparison run (polling endpoint).
     */
    public Optional<ComparisonStatusResponse> getStatus(String comparisonId) {
        ComparisonRun run = comparisons.get(comparisonId);
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(toStatusResponse(run));
    }

    private ComparisonStatusResponse toStatusResponse(ComparisonRun run) {
        return new ComparisonStatusResponse(
                run.getComparisonId(),
                run.getStatus().name(),
                buildResults(run),
                run.getError(),
                run.getStartedAt() != null
                        ? run.getStartedAt().atOffset(ZoneOffset.UTC).toString() : null,
                run.getCompletedAt() != null
                        ? run.getCompletedAt().atOffset(ZoneOffset.UTC).toString() : null,
                run.getReview()
        );
    }

    private List<CandidateResult> buildResults(ComparisonRun run) {
        return run.getSkills().stream()
                .map(spec -> {
                    ComparisonResult result = run.getResults().get(spec.coordinate());
                    if (result == null) {
                        // Skill not started yet
                        return new CandidateResult(
                                spec.coordinate(), spec.name(), null, 0, 0, null, null, null,
                                spec.sourceUrl(), List.of());
                    }
                    if (!result.isCompleted()) {
                        return new CandidateResult(
                                spec.coordinate(), spec.name(), null, 0,
                                result.getLatencySeconds(), null, null, null,
                                spec.sourceUrl(), List.of());
                    }
                    return new CandidateResult(
                            spec.coordinate(),
                            spec.name(),
                            result.getOutput(),
                            result.getTokensUsed(),
                            result.getLatencySeconds(),
                            result.getSkillApplied(),
                            result.getAppliedReason(),
                            result.getError(),
                            spec.sourceUrl(),
                            result.getFiles()
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Return config info for the frontend.
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxSkills", maxSkills);
        config.put("maxSkillsCap", maxSkillsCap);
        config.put("apiKeyConfigured", anthropicService.isAvailable());
        config.put("defaultModel", anthropicProperties.getModel() == null ? "" : anthropicProperties.getModel());
        config.put("showDefault", anthropicProperties.isShowDefaultProvider());
        config.put("providers", anthropicProperties.getProviders().entrySet().stream()
                .filter(e -> e.getValue().isConfigured())
                .map(e -> Map.of(
                        "id", e.getKey(),
                        "model", e.getValue().getModel() == null ? "" : e.getValue().getModel()))
                .toList());
        return config;
    }

    /**
     * Recent persisted comparison runs for a user, newest first (history list).
     */
    public List<ComparisonHistoryItem> getHistory(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        List<ForkprobeComparison> rows = comparisonRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, capped));
        return rows.stream().map(this::toHistoryItem).collect(Collectors.toList());
    }

    /**
     * Full results of a persisted comparison run, scoped to the owning user. Returns
     * empty if the run doesn't exist or belongs to another user.
     */
    public Optional<ComparisonStatusResponse> getHistoryDetail(String userId, String comparisonId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Optional<ForkprobeComparison> row = comparisonRepository.findByComparisonId(comparisonId);
        if (row.isEmpty() || !userId.equals(row.get().getUserId())) {
            return Optional.empty();
        }
        return Optional.of(toStatusResponse(row.get()));
    }

    /**
     * Delete a persisted comparison run, scoped to the owning user. Returns false
     * if the run doesn't exist or belongs to another user.
     */
    public boolean deleteHistory(String userId, String comparisonId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Optional<ForkprobeComparison> row = comparisonRepository.findByComparisonId(comparisonId);
        if (row.isEmpty() || !userId.equals(row.get().getUserId())) {
            return false;
        }
        comparisonRepository.delete(row.get());
        return true;
    }

    private ComparisonHistoryItem toHistoryItem(ForkprobeComparison row) {
        return new ComparisonHistoryItem(
                row.getComparisonId(),
                row.getTaskDescription(),
                row.getStatus(),
                row.getProvider(),
                parseResults(row.getResultsJson()).size(),
                row.getCreatedAt() != null
                        ? row.getCreatedAt().atOffset(ZoneOffset.UTC).toString() : null,
                row.getCompletedAt() != null
                        ? row.getCompletedAt().atOffset(ZoneOffset.UTC).toString() : null);
    }

    private ComparisonStatusResponse toStatusResponse(ForkprobeComparison row) {
        return new ComparisonStatusResponse(
                row.getComparisonId(),
                row.getStatus(),
                parseResults(row.getResultsJson()),
                row.getError(),
                row.getStartedAt() != null
                        ? row.getStartedAt().atOffset(ZoneOffset.UTC).toString() : null,
                row.getCompletedAt() != null
                        ? row.getCompletedAt().atOffset(ZoneOffset.UTC).toString() : null,
                null // AI review was disabled; persisted runs never carry a review
        );
    }

    /**
     * Deserialize the persisted results JSON array back into candidate results.
     * Parsed manually (rather than via record deserialization) to avoid needing
     * the Jackson ParameterNames module on this bare mapper.
     */
    private List<CandidateResult> parseResults(String resultsJson) {
        if (resultsJson == null || resultsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(resultsJson);
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<CandidateResult> results = new ArrayList<>();
            for (JsonNode node : arr) {
                results.add(new CandidateResult(
                        text(node, "skillCoordinate"),
                        text(node, "skillName"),
                        nullableText(node, "output"),
                        node.path("tokensUsed").asInt(0),
                        (float) node.path("latencySeconds").asDouble(0.0),
                        node.hasNonNull("skillApplied")
                                ? node.path("skillApplied").asBoolean() : null,
                        nullableText(node, "appliedReason"),
                        nullableText(node, "error"),
                        nullableText(node, "sourceUrl"),
                        parseFiles(node)));
            }
            return results;
        } catch (Exception e) {
            log.warn("Failed to parse persisted results: {}", e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * Deserialize the {@code files} array of a persisted candidate result back into
     * {@link OutputFile} records (base64 content is stored inline in the JSON).
     */
    private static List<OutputFile> parseFiles(JsonNode node) {
        JsonNode files = node.get("files");
        if (files == null || !files.isArray()) {
            return List.of();
        }
        List<OutputFile> out = new ArrayList<>();
        for (JsonNode f : files) {
            String name = nullableText(f, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(new OutputFile(
                    name,
                    f.path("sizeBytes").asLong(0),
                    nullableText(f, "contentType"),
                    nullableText(f, "contentBase64")));
        }
        return out;
    }

    /**
     * Normalise a provider id for persistence: blank/null or {@code "default"} means
     * "deployment default" and is stored as {@code null}.
     */
    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank() || "default".equalsIgnoreCase(provider)) {
            return null;
        }
        return provider;
    }

    /**
     * Resolve a provider id from the frontend into a concrete target. Blank/null or the
     * literal {@code "default"} means "use the deployment default" (no override). An
     * unknown id falls back to the default rather than failing the run.
     */
    private LlmTarget resolveTarget(String provider) {
        if (provider == null || provider.isBlank() || "default".equalsIgnoreCase(provider)) {
            return null;
        }
        AnthropicProperties.Provider p = anthropicProperties.getProviders().get(provider);
        if (p == null || !p.isConfigured()) {
            return null;
        }
        return new LlmTarget(p.getBaseUrl(), p.getApiKey(), p.getModel());
    }

    // --- Internal ---

    void executeComparison(String comparisonId) {
        ComparisonRun run = comparisons.get(comparisonId);
        if (run == null) return;

        run.setStatus(ComparisonRun.Status.RUNNING);
        log.info("Starting comparison {}: {} skills for task '{}'",
                comparisonId, run.getSkills().size(),
                run.getTaskDescription().length() > 50
                        ? run.getTaskDescription().substring(0, 50) + "..."
                        : run.getTaskDescription());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try {
            // Execute skills in parallel (max 3 concurrent)
            Semaphore semaphore = new Semaphore(3);

            for (ComparisonRun.SkillSpec spec : run.getSkills()) {
                if (run.isCancelled()) {
                    break;
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        executeOneSkill(run, spec);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        semaphore.release();
                    }
                }, comparisonExecutor);
                futures.add(future);
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.MINUTES);

            if (run.isCancelled()) {
                run.setStatus(ComparisonRun.Status.CANCELLED);
                log.info("Comparison {} cancelled", comparisonId);
                return;
            }

            // AI review has been disabled (product decision): the independent judge
            // produced verdicts that contradicted the per-skill "applied" flag, so it
            // is no longer surfaced. The comparison completes without a review pass.

            run.setStatus(ComparisonRun.Status.COMPLETED);
            log.info("Comparison {} completed: {} skills executed", comparisonId, run.getSkills().size());
        } catch (TimeoutException e) {
            run.setError("对比执行超时");
            run.setStatus(ComparisonRun.Status.FAILED);
            cancelInFlight(futures, run);
            log.warn("Comparison {} timed out", comparisonId);
        } catch (Exception e) {
            run.setError(e.getMessage());
            run.setStatus(ComparisonRun.Status.FAILED);
            cancelInFlight(futures, run);
            log.warn("Comparison {} failed: {}", comparisonId, e.getMessage());
        } finally {
            persistIfTerminal(run);
        }
    }

    /**
     * Signal and interrupt any still-running skill futures when a comparison fails.
     * The timeout/failure path marks the run terminal but — unlike a user cancel — was
     * not signalling the in-flight executions, so they would keep burning tokens (or
     * leave a docker container / claude subprocess alive) with no way to persist the
     * late result. {@link ComparisonRun#cancel()} flips the {@code run::isCancelled}
     * signal that subprocess/container executors poll; the future interrupts unblock
     * any blocking HTTP / process-wait call.
     */
    private void cancelInFlight(List<CompletableFuture<Void>> futures, ComparisonRun run) {
        run.cancel();
        for (CompletableFuture<Void> future : futures) {
            future.cancel(true);
        }
    }

    /**
     * Persist the run once it reaches a terminal state so the user can revisit it
     * after the in-memory store's TTL. Best-effort: persistence failures only log
     * and never affect the comparison outcome.
     */
    private void persistIfTerminal(ComparisonRun run) {
        ComparisonRun.Status status = run.getStatus();
        if (status == ComparisonRun.Status.COMPLETED
                || status == ComparisonRun.Status.FAILED
                || status == ComparisonRun.Status.CANCELLED) {
            persistRun(run);
        }
    }

    private void persistRun(ComparisonRun run) {
        if (run.getUserId() == null) {
            return; // anonymous runs are not retained
        }
        try {
            String resultsJson = objectMapper.writeValueAsString(buildResults(run));
            ForkprobeComparison entity = new ForkprobeComparison(
                    run.getComparisonId(),
                    run.getUserId(),
                    run.getTaskDescription(),
                    run.getProviderId(),
                    run.getStatus().name(),
                    resultsJson,
                    run.getError(),
                    run.getCreatedAt(),
                    run.getStartedAt(),
                    run.getCompletedAt());
            comparisonRepository.save(entity);
            log.info("Comparison {} persisted with status {}", run.getComparisonId(), run.getStatus().name());
        } catch (Exception e) {
            log.warn("Failed to persist comparison {}: {}", run.getComparisonId(), e.getMessage());
        }
    }

    private void executeOneSkill(ComparisonRun run, ComparisonRun.SkillSpec spec) {
        Instant start = Instant.now();
        ComparisonResult result = new ComparisonResult(spec.coordinate(), spec.name());
        run.addResult(spec.coordinate(), result);

        if (run.isCancelled()) {
            result.setError("已取消");
            return;
        }

        try {
            SkillExecutor.SkillResult sr = skillExecutor.execute(
                    spec.systemPrompt(), run.getTaskDescription(), spec.name(), run::isCancelled,
                    run.getTarget());

            result.setOutput(sr.output());
            result.setTokensUsed(sr.tokensUsed());
            result.setLatencySeconds(sr.latencySeconds());
            result.setFiles(sr.files());

            if (sr.error() != null) {
                result.setError(sr.error());
                return;
            }

            // Verify skill usage (skip verification for the baseline — it uses no
            // skill, so it stays null and renders no "skill applied" badge)
            if (!"baseline".equals(spec.coordinate())) {
                Boolean applied = skillExecutor.verify(
                        buildVerificationOutput(sr), spec.name(), spec.systemPrompt(), run.getTarget());
                result.setSkillApplied(applied);
                result.setAppliedReason(applied == null ? null
                        : applied ? "技能方法已应用于输出" : "该 skill 未调用");
            }
        } catch (Exception e) {
            log.warn("Skill '{}' execution error: {}", spec.name(), e.getMessage());
            float latency = (System.currentTimeMillis() - start.toEpochMilli()) / 1000.0f;
            result.setError(e.getMessage());
            result.setLatencySeconds(latency);
        }
    }

    /**
     * Build the text handed to the skill-applied verifier. In sandbox (docker) mode the
     * real deliverable is often a file (HTML, image, data table) written to {@code /output},
     * while {@code output} is only a terse summary ("已完成，文件见 /output/x.html"). Judging
     * just that summary wrongly scores the skill as "not applied" for lack of evidence. Append
     * the deliverable file names plus a short decoded preview (for text files) so the verifier
     * can see the actual work. The stored/displayed {@code output} is left untouched.
     */
    private String buildVerificationOutput(SkillExecutor.SkillResult sr) {
        List<OutputFile> files = sr.files();
        if (files == null || files.isEmpty()) {
            return sr.output();
        }
        StringBuilder sb = new StringBuilder(sr.output() == null ? "" : sr.output());
        sb.append("\n\n交付文件清单：\n");
        for (OutputFile f : files) {
            sb.append("- ").append(f.name())
                    .append(" (").append(f.sizeBytes()).append(" bytes, ").append(f.contentType()).append(")\n");
            String preview = textPreview(f);
            if (!preview.isBlank()) {
                sb.append(preview).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Decode a text deliverable and return a short UTF-8 preview, or "" for binary content
     * (image/audio/…) or anything that doesn't decode as UTF-8. Capped so the verification
     * prompt stays small.
     */
    private static String textPreview(OutputFile f) {
        String type = f.contentType() == null ? "" : f.contentType().toLowerCase();
        boolean textLike = type.startsWith("text/")
                || type.contains("json") || type.contains("xml")
                || type.contains("svg") || type.contains("html")
                || type.contains("javascript") || type.contains("yaml")
                || type.contains("csv");
        if (!textLike && !type.isBlank()) {
            return "";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(f.contentBase64());
            String text = new String(bytes, StandardCharsets.UTF_8);
            int cap = 600;
            String preview = text.length() > cap ? text.substring(0, cap) + "\n…(截断)…" : text;
            return "  内容预览：\n" + preview;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Run the independent AI review pass over completed candidate outputs.
     * <p>
     * Best-effort only: any failure (API error, unparseable JSON) leaves
     * {@code run.review} null and must not fail the comparison run.
     */
    private void runReview(ComparisonRun run) {
        List<ComparisonResult> completed = run.getSkills().stream()
                .map(spec -> run.getResults().get(spec.coordinate()))
                .filter(Objects::nonNull)
                .filter(r -> r.getOutput() != null && !r.getOutput().isBlank())
                .toList();

        // A meaningful review needs at least two real outputs to compare.
        if (completed.size() < 2) {
            log.info("Comparison {} skipped review: only {} completed output(s)",
                    run.getComparisonId(), completed.size());
            return;
        }

        try {
            String userMessage = buildReviewPrompt(run.getTaskDescription(), completed);
            ReviewResult review = null;
            // Reasoning models can burn the token budget on a "thinking" block and
            // return blank/unparseable content; retry a few times before giving up.
            for (int attempt = 0; attempt < 3 && review == null; attempt++) {
                AnthropicService.AnthropicMessageResponse response =
                        anthropicService.sendMessageWithRetry(REVIEW_SYSTEM_PROMPT, userMessage, reviewMaxTokens, 0, anthropicProperties.getJudgeModel());
                ReviewResult parsed = parseReview(response.content());
                review = parsed == null ? null : normalizeReview(parsed, completed);
                if (review == null) {
                    log.warn("Comparison {} review attempt {} returned blank/unparseable content; retrying",
                            run.getComparisonId(), attempt + 1);
                }
            }
            if (review != null) {
                run.setReview(review);
                log.info("Comparison {} review completed: winner={}",
                        run.getComparisonId(), review.winnerCoordinate());
            } else {
                log.warn("Comparison {} review returned unparseable content", run.getComparisonId());
            }
        } catch (Exception e) {
            log.warn("Comparison {} review failed: {}", run.getComparisonId(), e.getMessage());
        }
    }

    private String buildReviewPrompt(String taskDescription, List<ComparisonResult> completed) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(taskDescription).append("\n\n候选结果：\n\n");
        int idx = 1;
        for (ComparisonResult r : completed) {
            sb.append('[').append(idx++).append("] ")
                    .append(r.getSkillCoordinate()).append(" — ").append(r.getSkillName())
                    .append('\n')
                    .append(truncate(r.getOutput(), 1500))
                    .append("\n\n");
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        int half = maxChars / 2;
        return text.substring(0, half) + "\n...(truncated)...\n" + text.substring(text.length() - half);
    }

    /**
     * Parse the judge's JSON response. Tolerant of leading prose / markdown fences
     * by locating the outermost {@code { ... }} block.
     */
    private ReviewResult parseReview(String content) {
        if (content == null || content.isBlank()) return null;
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        json = json.substring(start, end + 1);

        try {
            JsonNode root = objectMapper.readTree(json);
            String winner = root.path("winner").asText("");
            String reason = root.path("reason").asText("");

            List<SkillReview> scores = new ArrayList<>();
            JsonNode scoresNode = root.path("scores");
            if (scoresNode.isArray()) {
                for (JsonNode s : scoresNode) {
                    String coord = s.path("coordinate").asText("");
                    int overall = s.path("overall").asInt(0);
                    List<ReviewScore> dimensions = new ArrayList<>();
                    JsonNode dimsNode = s.path("dimensions");
                    if (dimsNode.isArray()) {
                        for (JsonNode d : dimsNode) {
                            dimensions.add(new ReviewScore(
                                    d.path("label").asText(""),
                                    d.path("score").asInt(0)));
                        }
                    }
                    scores.add(new SkillReview(coord, overall, dimensions));
                }
            }

            if (winner.isBlank() && scores.isEmpty()) return null;
            return new ReviewResult(winner, reason, scores);
        } catch (Exception e) {
            log.warn("Failed to parse review JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalise the judge's candidate references back to raw coordinates.
     * <p>
     * The review prompt labels candidates as {@code [N] <coordinate> — <name>}; a
     * judge (especially a reasoning model) may echo that whole label instead of the
     * bare coordinate. The frontend matches {@code winnerCoordinate} /
     * {@code scores[].coordinate} against {@code CandidateResult.skillCoordinate},
     * so the raw coordinate must survive.
     */
    private ReviewResult normalizeReview(ReviewResult review, List<ComparisonResult> completed) {
        String winner = mapJudgeCoordinate(review.winnerCoordinate(), completed);
        List<SkillReview> scores = review.scores() == null ? List.of() : review.scores().stream()
                .map(s -> new SkillReview(
                        mapJudgeCoordinate(s.coordinate(), completed),
                        s.overall(),
                        s.dimensions()))
                .toList();
        return new ReviewResult(winner, review.winnerReason(), scores);
    }

    /**
     * Map a judge-returned candidate reference to its raw coordinate, tolerant of the
     * judge echoing the full {@code [N] coord — name} label or a fragment of it.
     */
    private String mapJudgeCoordinate(String value, List<ComparisonResult> completed) {
        if (value == null || value.isBlank()) return value;
        String v = value.trim();
        // 1) exact raw coordinate
        for (ComparisonResult r : completed) {
            if (v.equals(r.getSkillCoordinate())) return r.getSkillCoordinate();
        }
        // 2) judge echoed the full "[N] coord — name" label (the coord is a substring)
        for (ComparisonResult r : completed) {
            if (v.contains(r.getSkillCoordinate())) return r.getSkillCoordinate();
        }
        // 3) strip a leading "[N] " and trailing " — name", then retry exact
        String stripped = v;
        if (stripped.startsWith("[")) {
            int close = stripped.indexOf("] ");
            if (close > 0) stripped = stripped.substring(close + 2).trim();
        }
        int dash = stripped.indexOf(" — ");
        if (dash > 0) stripped = stripped.substring(0, dash).trim();
        for (ComparisonResult r : completed) {
            if (stripped.equals(r.getSkillCoordinate())) return r.getSkillCoordinate();
        }
        return value.trim();
    }

    private List<ComparisonRun.SkillSpec> resolveSkillSpecs(List<String> coordinates,
                                                            String userId,
                                                            Map<Long, NamespaceRole> userNsRoles) {
        List<ComparisonRun.SkillSpec> specs = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String coord : coordinates) {
            if ("baseline".equals(coord)) {
                specs.add(new ComparisonRun.SkillSpec(
                        "baseline", "基准参照 (Baseline)", "—",
                        DirectApiSkillExecutor.BASELINE_PROMPT, null));
                continue;
            }

            // SkillHub skill: format "namespace/slug"
            String[] parts = coord.split("/", 2);
            if (parts.length == 2) {
                try {
                    String prompt = loadSkillHubPrompt(parts[0], parts[1], userId, userNsRoles);
                    specs.add(new ComparisonRun.SkillSpec(
                            coord, parts[1], parts[0], prompt, null));
                } catch (Exception e) {
                    log.warn("Failed to load SKILL.md for {}/{}: {}", parts[0], parts[1], e.getMessage());
                    unreadable.add(coord);
                }
            } else {
                unreadable.add(coord);
            }
        }
        if (!unreadable.isEmpty()) {
            throw new IllegalArgumentException("无法读取以下 skill（可能未公开、不存在或已删除）: "
                    + String.join("、", unreadable));
        }
        return specs;
    }

    private String loadSkillHubPrompt(String namespace, String slug,
                                      String userId, Map<Long, NamespaceRole> userNsRoles) throws IOException {
        // Read SKILL.md for the latest published version, scoped to the requesting
        // user's visibility so a user comparing their own private / namespace-only skill
        // still gets its real content. A skill the user cannot see (or a read error)
        // propagates and is reported by resolveSkillSpecs rather than silently falling
        // back to a generic prompt that would produce a misleading comparison.
        InputStream stream = skillQueryService.getFileContentByTag(
                namespace, slug, "latest", "SKILL.md",
                userId, userNsRoles != null ? userNsRoles : Map.of());
        String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return stripFrontmatter(content);
    }

    static String stripFrontmatter(String markdown) {
        if (markdown == null) return "";
        String trimmed = markdown.trim();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("---", 3);
            if (end >= 0) {
                return trimmed.substring(end + 3).trim();
            }
        }
        return trimmed;
    }

    private RecommendedSkill baselineSkill() {
        return new RecommendedSkill(
                "baseline", "基准参照 (Baseline)", "—",
                "不加载任何 skill 的原始模型输出，作为对比基准", "baseline", "baseline", 0, null, false);
    }

    private void cleanupStaleComparisons() {
        Instant cutoff = Instant.now().minusSeconds(comparisonTtlMinutes * 60L);
        comparisons.entrySet().removeIf(entry -> {
            ComparisonRun run = entry.getValue();
            boolean isStale = run.getCreatedAt().isBefore(cutoff);
            // Also clean up completed/failed runs that are old
            boolean isDone = run.getStatus() == ComparisonRun.Status.COMPLETED
                    || run.getStatus() == ComparisonRun.Status.FAILED
                    || run.getStatus() == ComparisonRun.Status.CANCELLED;
            return isDone && isStale;
        });
        if (comparisons.size() > 100) {
            log.info("Comparison store has {} entries after cleanup", comparisons.size());
        }
    }

}
