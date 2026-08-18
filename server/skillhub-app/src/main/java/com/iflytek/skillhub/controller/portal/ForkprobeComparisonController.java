package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.forkprobe.CompareRequest;
import com.iflytek.skillhub.dto.forkprobe.CompareResponse;
import com.iflytek.skillhub.dto.forkprobe.ComparisonHistoryItem;
import com.iflytek.skillhub.dto.forkprobe.ComparisonStatusResponse;
import com.iflytek.skillhub.dto.forkprobe.RecommendRequest;
import com.iflytek.skillhub.dto.forkprobe.RecommendResponse;
import com.iflytek.skillhub.dto.forkprobe.RecommendedSkill;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.forkprobe.ForkprobeComparisonService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the forkprobe skill comparison sandbox.
 * <p>
 * Provides skill recommendation, comparison execution, and status polling.
 * All comparison state is in-memory — restarting the server clears inflight runs.
 */
@RestController
@RequestMapping({"/api/web/forkprobe"})
public class ForkprobeComparisonController extends BaseApiController {

    private final ForkprobeComparisonService comparisonService;

    public ForkprobeComparisonController(
            ForkprobeComparisonService comparisonService,
            ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.comparisonService = comparisonService;
    }

    /**
     * Get recommended skills for a task description.
     */
    @PostMapping("/recommend")
    @RateLimit(category = "forkprobe-recommend", authenticated = 30, anonymous = 10, windowSeconds = 60)
    public ApiResponse<RecommendResponse> recommend(
            @RequestBody @Valid RecommendRequest request,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        List<RecommendedSkill> candidates = comparisonService.recommend(
                request.taskDescription(),
                request.maxCandidates(),
                userId,
                userNsRoles);
        return ok("response.success.read", new RecommendResponse(candidates));
    }

    /**
     * Start a new skill comparison run.
     */
    @PostMapping("/compare")
    @RateLimit(category = "forkprobe-compare", authenticated = 10, anonymous = 3, windowSeconds = 60)
    public ApiResponse<CompareResponse> compare(
            @RequestBody @Valid CompareRequest request,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        CompareResponse response = comparisonService.startComparison(
                userId,
                request.taskDescription(),
                request.skillCoordinates(),
                request.provider(),
                userNsRoles);
        return ok("response.success.create", response);
    }

    /**
     * Poll for comparison status and results.
     * <p>
     * Frontend polls every 2 seconds while status is PENDING or RUNNING.
     * Returns 404 if the comparison ID doesn't exist (expired or never created).
     */
    @GetMapping("/compare/{comparisonId}")
    public ResponseEntity<ApiResponse<ComparisonStatusResponse>> status(
            @PathVariable String comparisonId) {
        Optional<ComparisonStatusResponse> status = comparisonService.getStatus(comparisonId);
        if (status.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ok("response.success.read", status.get()));
    }

    /**
     * Cancel an in-flight comparison run. Returns 404 if the comparison no longer
     * exists (already expired). Idempotent — cancelling an already-finished run
     * is a no-op.
     */
    @PostMapping("/compare/{comparisonId}/cancel")
    public ResponseEntity<ApiResponse<ComparisonStatusResponse>> cancel(
            @PathVariable String comparisonId) {
        boolean exists = comparisonService.cancelComparison(comparisonId);
        if (!exists) {
            return ResponseEntity.notFound().build();
        }
        Optional<ComparisonStatusResponse> status = comparisonService.getStatus(comparisonId);
        return ResponseEntity.ok(ok("response.success.update", status.orElse(null)));
    }

    /**
     * Get forkprobe configuration for the frontend.
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> config = comparisonService.getConfig();
        return ok("response.success.read", config);
    }

    /**
     * List the current user's persisted comparison runs, newest first.
     */
    @GetMapping("/history")
    public ApiResponse<List<ComparisonHistoryItem>> history(
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestParam(defaultValue = "20") int limit) {
        List<ComparisonHistoryItem> history = comparisonService.getHistory(userId, limit);
        return ok("response.success.read", history);
    }

    /**
     * Full results of a persisted comparison run, scoped to the owning user.
     * Returns 404 if the run doesn't exist or belongs to another user.
     */
    @GetMapping("/history/{comparisonId}")
    public ResponseEntity<ApiResponse<ComparisonStatusResponse>> historyDetail(
            @PathVariable String comparisonId,
            @RequestAttribute(value = "userId", required = false) String userId) {
        Optional<ComparisonStatusResponse> status =
                comparisonService.getHistoryDetail(userId, comparisonId);
        if (status.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ok("response.success.read", status.get()));
    }
}
