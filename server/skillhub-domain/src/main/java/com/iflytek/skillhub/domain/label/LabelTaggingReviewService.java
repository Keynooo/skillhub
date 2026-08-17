package com.iflytek.skillhub.domain.label;

import com.iflytek.skillhub.domain.event.LabelTaggingFailedEvent;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@link LabelTaggingReview} queue: a skill whose LLM auto-tagging returned no valid
 * labels is recorded as {@link LabelTaggingReviewStatus#PENDING} (and an admin is notified via
 * {@link LabelTaggingFailedEvent}); it moves to {@link LabelTaggingReviewStatus#RESOLVED} once a
 * human labels it or dismisses it.
 *
 * <p>This is the "no silent failure" safety net for the auto-tagging pipeline — a misbehaving LLM
 * can never silently leave a skill unfilterable; the skill lands in the review queue instead.
 */
@Service
public class LabelTaggingReviewService {

    private static final Logger log = LoggerFactory.getLogger(LabelTaggingReviewService.class);

    private final SkillRepository skillRepository;
    private final LabelTaggingReviewRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LabelTaggingReviewService(SkillRepository skillRepository,
                                     LabelTaggingReviewRepository repository,
                                     ApplicationEventPublisher eventPublisher,
                                     Clock clock) {
        this.skillRepository = skillRepository;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Records (or re-opens) a pending review for a skill that failed auto-tagging, and notifies
     * platform skill admins. Idempotent per skill: an existing review is updated rather than
     * duplicated.
     */
    @Transactional
    public LabelTaggingReview markPending(Long skillId, String reason) {
        LabelTaggingReview existing = repository.findBySkillId(skillId).orElse(null);
        if (existing != null) {
            existing.setStatus(LabelTaggingReviewStatus.PENDING);
            existing.setReason(reason);
            existing.setResolvedAt(null);
            existing.setResolvedBy(null);
            LabelTaggingReview saved = repository.save(existing);
            eventPublisher.publishEvent(new LabelTaggingFailedEvent(skillId));
            return saved;
        }

        Skill skill = skillRepository.findById(skillId).orElse(null);
        String skillName = skill == null ? null : displayName(skill);
        String skillSlug = skill == null ? null : skill.getSlug();
        Long namespaceId = skill == null ? null : skill.getNamespaceId();
        LabelTaggingReview review = new LabelTaggingReview(skillId, namespaceId, skillName, skillSlug, reason);
        LabelTaggingReview saved = repository.save(review);
        log.info("Skill queued for manual labeling: skillId={}, reason={}", skillId, reason);
        eventPublisher.publishEvent(new LabelTaggingFailedEvent(skillId));
        return saved;
    }

    /**
     * Clears the pending review for a skill that is now labeled (auto-tag succeeded or a human
     * labeled it). No-op when the skill has no pending review.
     */
    @Transactional
    public void markResolved(Long skillId, String operatorId) {
        repository.findBySkillId(skillId).ifPresent(review -> {
            if (review.getStatus() == LabelTaggingReviewStatus.PENDING) {
                review.setStatus(LabelTaggingReviewStatus.RESOLVED);
                review.setResolvedBy(operatorId);
                review.setResolvedAt(Instant.now(clock));
                repository.save(review);
                log.info("Label tagging review resolved: skillId={}, by={}", skillId, operatorId);
            }
        });
    }

    public Page<LabelTaggingReview> listPending(Pageable pageable) {
        return repository.findByStatus(LabelTaggingReviewStatus.PENDING, pageable);
    }

    private String displayName(Skill skill) {
        String name = skill.getDisplayName();
        return (name != null && !name.isBlank()) ? name : skill.getSlug();
    }
}
