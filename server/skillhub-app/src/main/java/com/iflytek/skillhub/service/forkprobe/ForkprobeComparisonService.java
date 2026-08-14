package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.dto.forkprobe.CandidateResult;
import com.iflytek.skillhub.dto.forkprobe.CompareResponse;
import com.iflytek.skillhub.dto.forkprobe.ComparisonStatusResponse;
import com.iflytek.skillhub.dto.forkprobe.RecommendedSkill;
import com.iflytek.skillhub.dto.forkprobe.ReviewResult;
import com.iflytek.skillhub.dto.forkprobe.ReviewScore;
import com.iflytek.skillhub.dto.forkprobe.SkillReview;
import com.iflytek.skillhub.service.AnthropicService;
import com.iflytek.skillhub.service.SkillSearchAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final ConcurrentHashMap<String, ComparisonRun> comparisons = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    private final SkillQueryService skillQueryService;
    private final SkillSearchAppService skillSearchAppService;
    private final AnthropicService anthropicService;
    private final SkillExecutor skillExecutor;
    private final ExecutorService comparisonExecutor;

    private final int maxSkills;
    private final int maxSkillsCap;
    private final int comparisonTtlMinutes;
    private final int reviewMaxTokens;
    private final String judgeModel;

    public ForkprobeComparisonService(
            SkillQueryService skillQueryService,
            SkillSearchAppService skillSearchAppService,
            AnthropicService anthropicService,
            AnthropicProperties anthropicProperties,
            ForkprobeExecutorProperties executorProperties,
            Semaphore sandboxSemaphore,
            @Value("${skillhub.forkprobe.max-skills:3}") int maxSkills,
            @Value("${skillhub.forkprobe.max-skills-cap:5}") int maxSkillsCap,
            @Value("${skillhub.forkprobe.comparison-ttl-minutes:30}") int comparisonTtlMinutes,
            @Value("${skillhub.forkprobe.review-max-tokens:4096}") int reviewMaxTokens) {
        this.skillQueryService = skillQueryService;
        this.skillSearchAppService = skillSearchAppService;
        this.anthropicService = anthropicService;
        this.maxSkills = maxSkills;
        this.maxSkillsCap = maxSkillsCap;
        this.comparisonTtlMinutes = comparisonTtlMinutes;
        this.reviewMaxTokens = reviewMaxTokens;
        this.judgeModel = anthropicProperties.getJudgeModel();
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
     * Searches the platform's own published skills (visibility-scoped to the
     * requesting user) by relevance to the task, and returns them alongside a
     * baseline candidate. Recommending only skills that already exist on the
     * platform keeps the comparison meaningful: every candidate resolves to a
     * real SKILL.md the user can open and reuse.
     */
    public List<RecommendedSkill> recommend(String taskDescription, int maxCandidates,
                                            String userId, Map<Long, NamespaceRole> userNsRoles) {
        List<RecommendedSkill> candidates = new ArrayList<>();
        // Baseline always first
        candidates.add(baselineSkill());

        try {
            // Lexical relevance search first. The full-text engine ANDs every token, so a
            // long/free-form task sentence usually matches nothing — fall back to the
            // platform's visible skills so the user always has real platform skills to pick.
            List<SkillSummaryResponse> matched =
                    searchSkills(taskDescription, maxCandidates, userId, userNsRoles);
            if (matched.isEmpty()) {
                matched = searchSkills(null, maxCandidates, userId, userNsRoles);
            }

            for (SkillSummaryResponse skill : matched) {
                if (candidates.size() >= maxCandidates + 1) break; // +1 for baseline
                candidates.add(toRecommendedSkill(skill));
            }
        } catch (Exception e) {
            log.warn("Platform skill recommendation failed: {}", e.getMessage());
        }

        return candidates;
    }

    private List<SkillSummaryResponse> searchSkills(String keyword, int size,
                                                    String userId, Map<Long, NamespaceRole> userNsRoles) {
        String sort = (keyword == null || keyword.isBlank()) ? "newest" : "relevance";
        return skillSearchAppService.search(
                keyword, null, sort, 0, size, List.of(), userId, userNsRoles).items();
    }

    private RecommendedSkill toRecommendedSkill(SkillSummaryResponse skill) {
        String namespace = skill.namespace() != null ? skill.namespace() : "";
        String coordinate = namespace.isBlank() ? skill.slug() : namespace + "/" + skill.slug();
        String name = skill.displayName() != null && !skill.displayName().isBlank()
                ? skill.displayName() : skill.slug();
        String reasonZh = skill.summary() != null && !skill.summary().isBlank()
                ? skill.summary() : "平台技能，可直接加入对比";
        int stars = skill.starCount() != null ? skill.starCount() : 0;
        return new RecommendedSkill(
                coordinate, name, namespace, reasonZh, "skillhub", "skillhub", stars, null);
    }

    /**
     * Start a new comparison run.
     */
    public CompareResponse startComparison(String taskDescription, List<String> skillCoordinates) {
        // Validate
        if (skillCoordinates.size() > maxSkillsCap) {
            throw new IllegalArgumentException("最多只能选择 " + maxSkillsCap + " 个 skill");
        }

        // Resolve skill specs
        List<ComparisonRun.SkillSpec> specs = resolveSkillSpecs(skillCoordinates);
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("没有找到任何可执行的 skill");
        }

        // Create run
        ComparisonRun run = new ComparisonRun(taskDescription, specs);
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

        List<CandidateResult> results = run.getSkills().stream()
                .map(spec -> {
                    ComparisonResult result = run.getResults().get(spec.coordinate());
                    if (result == null) {
                        // Skill not started yet
                        return new CandidateResult(
                                spec.coordinate(), spec.name(), null, 0, 0, null, null, null,
                                spec.sourceUrl());
                    }
                    if (!result.isCompleted()) {
                        return new CandidateResult(
                                spec.coordinate(), spec.name(), null, 0,
                                result.getLatencySeconds(), null, null, null,
                                spec.sourceUrl());
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
                            spec.sourceUrl()
                    );
                })
                .collect(Collectors.toList());

        return Optional.of(new ComparisonStatusResponse(
                run.getComparisonId(),
                run.getStatus().name(),
                results,
                run.getError(),
                run.getStartedAt() != null
                        ? run.getStartedAt().atOffset(ZoneOffset.UTC).toString() : null,
                run.getCompletedAt() != null
                        ? run.getCompletedAt().atOffset(ZoneOffset.UTC).toString() : null,
                run.getReview()
        ));
    }

    /**
     * Return config info for the frontend.
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxSkills", maxSkills);
        config.put("maxSkillsCap", maxSkillsCap);
        config.put("apiKeyConfigured", anthropicService.isAvailable());
        return config;
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

        try {
            // Execute skills in parallel (max 3 concurrent)
            Semaphore semaphore = new Semaphore(3);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

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
            log.warn("Comparison {} timed out", comparisonId);
        } catch (Exception e) {
            run.setError(e.getMessage());
            run.setStatus(ComparisonRun.Status.FAILED);
            log.warn("Comparison {} failed: {}", comparisonId, e.getMessage());
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
                    spec.systemPrompt(), run.getTaskDescription(), spec.name(), run::isCancelled);

            result.setOutput(sr.output());
            result.setTokensUsed(sr.tokensUsed());
            result.setLatencySeconds(sr.latencySeconds());

            if (sr.error() != null) {
                result.setError(sr.error());
                return;
            }

            // Verify skill usage (skip verification for baseline)
            if (!"baseline".equals(spec.coordinate())) {
                boolean applied = skillExecutor.verify(
                        sr.output(), spec.name(), spec.systemPrompt());
                result.setSkillApplied(applied);
                result.setAppliedReason(applied ? "技能方法已应用于输出" : "该 skill 未调用");
            } else {
                // Baseline is always "applied" (it's the reference)
                result.setSkillApplied(true);
                result.setAppliedReason("基准参照");
            }
        } catch (Exception e) {
            log.warn("Skill '{}' execution error: {}", spec.name(), e.getMessage());
            float latency = (System.currentTimeMillis() - start.toEpochMilli()) / 1000.0f;
            result.setError(e.getMessage());
            result.setLatencySeconds(latency);
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
                        anthropicService.sendMessageWithRetry(REVIEW_SYSTEM_PROMPT, userMessage, reviewMaxTokens, 0, judgeModel);
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

    private List<ComparisonRun.SkillSpec> resolveSkillSpecs(List<String> coordinates) {
        List<ComparisonRun.SkillSpec> specs = new ArrayList<>();
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
                    String prompt = loadSkillHubPrompt(parts[0], parts[1]);
                    specs.add(new ComparisonRun.SkillSpec(
                            coord, parts[1], parts[0], prompt, null));
                } catch (Exception e) {
                    log.warn("Failed to load SKILL.md for {}/{}: {}", parts[0], parts[1], e.getMessage());
                }
            }
        }
        return specs;
    }

    private String loadSkillHubPrompt(String namespace, String slug) {
        try {
            // Try to read SKILL.md for the latest published version
            InputStream stream = skillQueryService.getFileContent(
                    namespace, slug, "latest", "SKILL.md",
                    null, Map.of());
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            // Strip YAML frontmatter (between --- markers)
            return stripFrontmatter(content);
        } catch (Exception e) {
            log.warn("Could not read SKILL.md for {}/{}: {}", namespace, slug, e.getMessage());
            return "You are a helpful assistant. Skill: " + slug + " from namespace " + namespace + ".";
        }
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
                "不加载任何 skill 的原始模型输出，作为对比基准", "baseline", "baseline", 0, null);
    }

    private void cleanupStaleComparisons() {
        Instant cutoff = Instant.now().minusSeconds(comparisonTtlMinutes * 60L);
        comparisons.entrySet().removeIf(entry -> {
            ComparisonRun run = entry.getValue();
            boolean isStale = run.getCreatedAt().isBefore(cutoff);
            // Also clean up completed/failed runs that are old
            boolean isDone = run.getStatus() == ComparisonRun.Status.COMPLETED
                    || run.getStatus() == ComparisonRun.Status.FAILED;
            return isDone && isStale;
        });
        if (comparisons.size() > 100) {
            log.info("Comparison store has {} entries after cleanup", comparisons.size());
        }
    }

}
