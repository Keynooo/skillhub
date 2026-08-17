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
import org.springframework.stereotype.Component;

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
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.summary-translation", name = "backfill-on-startup",
        havingValue = "true", matchIfMissing = true)
public class SkillSummaryBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryBackfillRunner.class);

    private final SkillRepository skillRepository;
    private final SummaryTranslator summaryTranslator;

    public SkillSummaryBackfillRunner(SkillRepository skillRepository, SummaryTranslator summaryTranslator) {
        this.skillRepository = skillRepository;
        this.summaryTranslator = summaryTranslator;
    }

    @Async("skillhubEventExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingTranslations() {
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
    }
}
