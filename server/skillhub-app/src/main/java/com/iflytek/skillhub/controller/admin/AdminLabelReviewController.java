package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.LabelTaggingReviewResolveRequest;
import com.iflytek.skillhub.dto.LabelTaggingReviewResponse;
import com.iflytek.skillhub.dto.MessageResponse;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.service.LabelTaggingReviewAppService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for the label-tagging review queue: skills whose LLM auto-tagging returned no
 * valid labels are listed here for a human to label manually.
 */
@RestController
@RequestMapping("/api/v1/admin/label-tagging-reviews")
public class AdminLabelReviewController extends BaseApiController {

    private final LabelTaggingReviewAppService appService;

    public AdminLabelReviewController(ApiResponseFactory responseFactory,
                                      LabelTaggingReviewAppService appService) {
        super(responseFactory);
        this.appService = appService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SKILL_ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<PageResponse<LabelTaggingReviewResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success", appService.list(page, size));
    }

    @PostMapping("/{skillId}/resolve")
    @PreAuthorize("hasAnyRole('SKILL_ADMIN', 'SUPER_ADMIN')")
    public ApiResponse<MessageResponse> resolve(
            @PathVariable Long skillId,
            @RequestBody(required = false) LabelTaggingReviewResolveRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        appService.resolve(skillId, request, principal.userId());
        return ok("response.success.updated", new MessageResponse("Label tagging review resolved"));
    }
}
