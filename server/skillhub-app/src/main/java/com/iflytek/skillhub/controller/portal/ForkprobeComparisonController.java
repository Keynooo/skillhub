package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.forkprobe.CompareRequest;
import com.iflytek.skillhub.dto.forkprobe.CompareResponse;
import com.iflytek.skillhub.dto.forkprobe.ComparisonHistoryItem;
import com.iflytek.skillhub.dto.forkprobe.ComparisonStatusResponse;
import com.iflytek.skillhub.dto.forkprobe.PipelineHistoryItem;
import com.iflytek.skillhub.dto.forkprobe.PipelineRequest;
import com.iflytek.skillhub.dto.forkprobe.PipelineResponse;
import com.iflytek.skillhub.dto.forkprobe.PipelineStatusResponse;
import com.iflytek.skillhub.dto.forkprobe.AutopilotRequest;
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

    /**
     * Start a new skill pipeline (编排) run — three parallel lanes, each a serial
     * chain of the skills the user picked (auto-ordered by the system).
     */
    @PostMapping("/pipeline")
    @RateLimit(category = "forkprobe-pipeline", authenticated = 10, anonymous = 3, windowSeconds = 60)
    public ApiResponse<PipelineResponse> pipeline(
            @RequestBody @Valid PipelineRequest request,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        PipelineResponse response = comparisonService.startPipeline(
                userId,
                request.taskDescription(),
                request.lanes(),
                request.provider(),
                userNsRoles);
        return ok("response.success.create", response);
    }

    /**
     * Start an autopilot (AI-orchestrated) pipeline run: the user nominates a pool of
     * 0..5 candidate skills, and an LLM orchestrator (aware of each candidate's SKILL.md)
     * decides the actual subset + order. The resulting AI chain is polled/managed through
     * the same pipeline endpoints as the manual run, and runs alongside a baseline lane.
     */
    @PostMapping("/pipeline/autopilot")
    @RateLimit(category = "forkprobe-pipeline", authenticated = 10, anonymous = 3, windowSeconds = 60)
    public ApiResponse<PipelineResponse> autopilot(
            @RequestBody @Valid AutopilotRequest request,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        PipelineResponse response = comparisonService.startAutopilotPipeline(
                userId,
                request.taskDescription(),
                request.skillCoordinates() == null ? java.util.List.of() : request.skillCoordinates(),
                request.provider(),
                userNsRoles);
        return ok("response.success.create", response);
    }

    /**
     * Poll for pipeline status and stage results. Returns 404 if the pipeline ID
     * doesn't exist (expired or never created).
     */
    @GetMapping("/pipeline/{pipelineId}")
    public ResponseEntity<ApiResponse<PipelineStatusResponse>> pipelineStatus(
            @PathVariable String pipelineId) {
        Optional<PipelineStatusResponse> status = comparisonService.getPipelineStatus(pipelineId);
        if (status.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ok("response.success.read", status.get()));
    }

    /**
     * Cancel an in-flight pipeline run. Idempotent — cancelling an already-finished
     * run is a no-op.
     */
    @PostMapping("/pipeline/{pipelineId}/cancel")
    public ResponseEntity<ApiResponse<PipelineStatusResponse>> pipelineCancel(
            @PathVariable String pipelineId) {
        boolean exists = comparisonService.cancelPipeline(pipelineId);
        if (!exists) {
            return ResponseEntity.notFound().build();
        }
        Optional<PipelineStatusResponse> status = comparisonService.getPipelineStatus(pipelineId);
        return ResponseEntity.ok(ok("response.success.update", status.orElse(null)));
    }

    /**
     * List the current user's persisted pipeline runs, newest first.
     */
    @GetMapping("/pipeline/history")
    public ApiResponse<List<PipelineHistoryItem>> pipelineHistory(
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestParam(defaultValue = "20") int limit) {
        List<PipelineHistoryItem> history = comparisonService.getPipelineHistory(userId, limit);
        return ok("response.success.read", history);
    }

    /**
     * Full results of a persisted pipeline run, scoped to the owning user.
     * Returns 404 if the run doesn't exist or belongs to another user.
     */
    @GetMapping("/pipeline/history/{pipelineId}")
    public ResponseEntity<ApiResponse<PipelineStatusResponse>> pipelineHistoryDetail(
            @PathVariable String pipelineId,
            @RequestAttribute(value = "userId", required = false) String userId) {
        Optional<PipelineStatusResponse> status =
                comparisonService.getPipelineHistoryDetail(userId, pipelineId);
        if (status.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ok("response.success.read", status.get()));
    }
}
