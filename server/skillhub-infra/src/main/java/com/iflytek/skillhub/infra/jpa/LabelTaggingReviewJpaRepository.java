package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.label.LabelTaggingReview;
import com.iflytek.skillhub.domain.label.LabelTaggingReviewRepository;
import com.iflytek.skillhub.domain.label.LabelTaggingReviewStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LabelTaggingReviewJpaRepository
        extends JpaRepository<LabelTaggingReview, Long>, LabelTaggingReviewRepository {
    Optional<LabelTaggingReview> findBySkillId(Long skillId);
    Page<LabelTaggingReview> findByStatusOrderByCreatedAtDesc(LabelTaggingReviewStatus status, Pageable pageable);
    void deleteBySkillId(Long skillId);

    @Override
    default Page<LabelTaggingReview> findByStatus(LabelTaggingReviewStatus status, Pageable pageable) {
        return findByStatusOrderByCreatedAtDesc(status, pageable);
    }
}
