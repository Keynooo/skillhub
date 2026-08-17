package com.iflytek.skillhub.domain.label;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Domain repository contract for {@link LabelTaggingReview}.
 */
public interface LabelTaggingReviewRepository {
    LabelTaggingReview save(LabelTaggingReview review);
    Optional<LabelTaggingReview> findBySkillId(Long skillId);
    Page<LabelTaggingReview> findByStatus(LabelTaggingReviewStatus status, Pageable pageable);
    void deleteBySkillId(Long skillId);
}
