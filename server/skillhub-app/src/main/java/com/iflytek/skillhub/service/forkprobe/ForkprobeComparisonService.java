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
            // Deterministic semantic recall over the platform's own published skills:
            // rank the visible pool by lexical-hash vector similarity to the task text.
            // The same task description always returns the same candidates — no fragile
            // keyword full-text AND, no non-deterministic LLM re-pick.
            List<SkillSummaryResponse> matched =
                    skillSearchAppService.semanticSearch(taskDescription, maxCandidates, userId, userNsRoles);

            for (SkillSummaryResponse skill : matched) {
                if (candidates.size() >= maxCandidates + 1) break; // +1 for baseline
                candidates.add(toRecommendedSkill(skill));
            }
        } catch (Exception e) {
            log.warn("Platform skill recommendation failed: {}", e.getMessage());
        }

        return candidates;
    }

    private RecommendedSkill toRecommendedSkill(SkillSummaryResponse skill) {
        String namespace = skill.namespace() != null ? skill.namespace() : "";
        String name = skill.displayName() != null && !skill.displayName().isBlank()
                ? skill.displayName() : skill.slug();
        String reasonZh = skill.summary() != null && !skill.summary().isBlank()
                ? skill.summary() : "平台技能，可直接加入对比";
        int stars = skill.starCount() != null ? skill.starCount() : 0;
        return new RecommendedSkill(
                coordinateOf(skill), name, namespace, reasonZh, "skillhub", "skillhub", stars, null);
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
                        nullableText(node, "sourceUrl")));
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

            if (sr.error() != null) {
                result.setError(sr.error());
                return;
            }

            // Verify skill usage (skip verification for the baseline — it uses no
            // skill, so it stays null and renders no "skill applied" badge)
            if (!"baseline".equals(spec.coordinate())) {
                Boolean applied = skillExecutor.verify(
                        sr.output(), spec.name(), spec.systemPrompt(), run.getTarget());
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
                "不加载任何 skill 的原始模型输出，作为对比基准", "baseline", "baseline", 0, null);
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
