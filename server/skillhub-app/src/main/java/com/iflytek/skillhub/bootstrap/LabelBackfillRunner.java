package com.iflytek.skillhub.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.label.LabelTask;
import com.iflytek.skillhub.domain.label.LabelTaskProducer;
import com.iflytek.skillhub.domain.label.SkillLabelRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-shot startup backfill that re-tags every published skill after the scenario-taxonomy switch.
 *
 * <p>The V46 migration deletes the old capability labels, which cascades to {@code skill_label} and
 * leaves every existing skill untagged. When {@code skillhub.label.auto-tagging.backfill-on-startup}
 * is true (set only on the first boot after the taxonomy change), this runner enqueues a
 * {@link LabelTask} for each skill that has a published version but no labels yet. It is idempotent:
 * once the background consumer attaches labels, subsequent boots skip the skill.
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.label.auto-tagging", name = "backfill-on-startup", havingValue = "true")
public class LabelBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LabelBackfillRunner.class);
    private static final int MAX_BODY_CHARS = 3000;

    private final boolean autoTaggingEnabled;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillLabelRepository skillLabelRepository;
    private final LabelTaskProducer labelTaskProducer;
    private final ObjectMapper objectMapper;

    public LabelBackfillRunner(@Value("${skillhub.label.auto-tagging.enabled:false}") boolean autoTaggingEnabled,
                               SkillRepository skillRepository,
                               SkillVersionRepository skillVersionRepository,
                               SkillLabelRepository skillLabelRepository,
                               LabelTaskProducer labelTaskProducer,
                               ObjectMapper objectMapper) {
        this.autoTaggingEnabled = autoTaggingEnabled;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillLabelRepository = skillLabelRepository;
        this.labelTaskProducer = labelTaskProducer;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoTaggingEnabled) {
            log.info("Label backfill skipped: auto-tagging is disabled");
            return;
        }

        int enqueued = 0;
        for (Skill skill : skillRepository.findAll()) {
            try {
                if (skillLabelRepository.countBySkillId(skill.getId()) > 0) {
                    continue;
                }
                SkillVersion published = latestPublishedVersion(skill.getId());
                if (published == null) {
                    continue;
                }
                labelTaskProducer.publishLabelTask(new LabelTask(
                        UUID.randomUUID().toString(),
                        skill.getId(),
                        skill.getDisplayName() != null ? skill.getDisplayName() : skill.getSlug(),
                        skill.getSummary(),
                        truncateForLabeling(extractBody(published)),
                        skill.getOwnerId(),
                        System.currentTimeMillis(),
                        Map.of("source", "backfill")
                ));
                enqueued++;
            } catch (RuntimeException e) {
                log.warn("Failed to enqueue label backfill for skillId={}: {}",
                        skill.getId(), e.getMessage());
            }
        }
        log.info("Label backfill enqueued {} skill(s)", enqueued);
    }

    private SkillVersion latestPublishedVersion(Long skillId) {
        return skillVersionRepository.findBySkillIdAndStatus(skillId, SkillVersionStatus.PUBLISHED)
                .stream()
                .max(Comparator.comparing(SkillVersion::getPublishedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private String extractBody(SkillVersion version) {
        String json = version.getParsedMetadataJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode body = root.get("body");
            return body == null || body.isNull() ? null : body.asText("");
        } catch (Exception e) {
            log.warn("Failed to parse metadata body for versionId={}: {}", version.getId(), e.getMessage());
            return null;
        }
    }

    private static String truncateForLabeling(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int half = MAX_BODY_CHARS / 2;
        return body.substring(0, half) + "\n...(truncated)...\n" + body.substring(body.length() - half);
    }
}
