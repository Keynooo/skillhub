package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SummaryTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backfills the {@code summary_zh} column for skills that were published before AI summary
 * translation existed.
 *
 * <p>Runs asynchronously after application startup and is idempotent: it only translates a skill
 * whose {@code summaryZh} is still blank (and whose {@code summary} is non-Chinese — the translator
 * itself skips such text), then persists it. On subsequent boots every skill is already translated
 * and the pass is a cheap no-op; skills whose translation previously failed are retried. When no
 * translation provider is configured the translator returns {@code null} for every skill, so the
 * pass exits immediately.
 *
 * <p>The startup pass is backed by a periodic {@link Scheduled} pass. The provider (e.g. GLM) can be
 * transiently overloaded (HTTP 529), in which case a single startup pass would leave most skills
 * untranslated until the next restart. The periodic pass retries any still-missing skills on a fixed
 * delay, so the backfill self-heals once the provider recovers. A {@link AtomicBoolean} guard keeps
 * concurrent passes from overlapping (the async executor has several threads).
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.summary-translation", name = "backfill-on-startup",
        havingValue = "true", matchIfMissing = true)
public class SkillSummaryBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryBackfillRunner.class);

    private final SkillRepository skillRepository;
    private final SummaryTranslator summaryTranslator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SkillSummaryBackfillRunner(SkillRepository skillRepository, SummaryTranslator summaryTranslator) {
        this.skillRepository = skillRepository;
        this.summaryTranslator = summaryTranslator;
    }

    @Async("skillhubEventExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        backfillMissingTranslations();
    }

    /**
     * Periodic retry pass. First run fires shortly after startup and then repeats on a fixed delay,
     * so skills whose translation failed (e.g. the provider was overloaded) are retried until they
     * succeed. The guard drops a pass if the previous one is still in flight.
     */
    @Async("skillhubEventExecutor")
    @Scheduled(
            fixedDelayString = "${skillhub.summary-translation.backfill-interval-ms:1800000}",
            initialDelayString = "${skillhub.summary-translation.backfill-initial-delay-ms:60000}"
    )
    public void backfillPeriodically() {
        backfillMissingTranslations();
    }

    private void backfillMissingTranslations() {
        if (!running.compareAndSet(false, true)) {
            log.info("Summary translation backfill already running, skipping this pass");
            return;
        }
        try {
            int scanned = 0;
            int translated = 0;
            for (Skill skill : skillRepository.findAll()) {
                scanned++;
                try {
                    if (skill.getSummaryZh() != null && !skill.getSummaryZh().isBlank()) {
                        continue;
                    }
                    String translatedText = summaryTranslator.translateToChinese(skill.getSummary());
                    if (translatedText == null) {
                        continue;
                    }
                    skill.setSummaryZh(translatedText);
                    skillRepository.save(skill);
                    translated++;
                } catch (RuntimeException e) {
                    log.warn("Failed to backfill summary translation for skillId={}: {}",
                            skill.getId(), e.getMessage());
                }
            }
            log.info("Summary translation backfill finished: scanned={}, translated={}", scanned, translated);
        } finally {
            running.set(false);
        }
    }
}
