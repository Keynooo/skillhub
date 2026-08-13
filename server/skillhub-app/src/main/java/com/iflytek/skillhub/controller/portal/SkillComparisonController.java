package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.SkillSearchAppService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal endpoint for cross-skill comparison discovery: returns skills similar
 * to the target skill, feeding the forkprobe "compare before download" flow.
 */
@RestController
@RequestMapping({"/api/web/skills"})
public class SkillComparisonController extends BaseApiController {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    private final SkillSearchAppService skillSearchAppService;

    public SkillComparisonController(SkillSearchAppService skillSearchAppService,
                                     ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.skillSearchAppService = skillSearchAppService;
    }

    @GetMapping("/{namespace}/{slug}/similar")
    @RateLimit(category = "search", authenticated = 60, anonymous = 20)
    public ApiResponse<List<SkillSummaryResponse>> similarSkills(
            @PathVariable String namespace,
            @PathVariable String slug,
            @RequestParam(required = false) Integer limit,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {

        int resolvedLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        List<SkillSummaryResponse> similar = skillSearchAppService.similarSkills(
                namespace, slug, resolvedLimit, userId, userNsRoles);

        return ok("response.success.read", similar);
    }
}
