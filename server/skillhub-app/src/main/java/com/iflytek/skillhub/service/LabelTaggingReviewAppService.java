package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.label.LabelTaggingReview;
import com.iflytek.skillhub.domain.label.LabelTaggingReviewService;
import com.iflytek.skillhub.domain.label.SkillLabelService;
import com.iflytek.skillhub.dto.LabelTaggingReviewResolveRequest;
import com.iflytek.skillhub.dto.LabelTaggingReviewResponse;
import com.iflytek.skillhub.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the admin "manual labeling" queue: lists skills that failed LLM
 * auto-tagging and resolves them (optionally attaching labels in the same call).
 */
@Service
public class LabelTaggingReviewAppService {

    private final LabelTaggingReviewService reviewService;
    private final SkillLabelService skillLabelService;

    public LabelTaggingReviewAppService(LabelTaggingReviewService reviewService,
                                        SkillLabelService skillLabelService) {
        this.reviewService = reviewService;
        this.skillLabelService = skillLabelService;
    }

    public PageResponse<LabelTaggingReviewResponse> list(int page, int size) {
        return PageResponse.from(reviewService.listPending(PageRequest.of(page, size))
                .map(this::toResponse));
    }

    @Transactional
    public void resolve(Long skillId, LabelTaggingReviewResolveRequest request, String operatorId) {
        if (request != null && request.labels() != null && !request.labels().isEmpty()) {
            skillLabelService.autoTag(skillId, request.labels(), operatorId);
        }
        reviewService.markResolved(skillId, operatorId);
    }

    private LabelTaggingReviewResponse toResponse(LabelTaggingReview review) {
        return new LabelTaggingReviewResponse(
                review.getSkillId(),
                review.getNamespaceId(),
                review.getSkillName(),
                review.getSkillSlug(),
                review.getReason(),
                review.getCreatedAt()
        );
    }
}
