package com.iflytek.skillhub.domain.label;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillLabelService {

    private static final Logger log = LoggerFactory.getLogger(SkillLabelService.class);

    private final int maxLabelsPerSkill;

    private final SkillRepository skillRepository;
    private final LabelDefinitionRepository labelDefinitionRepository;
    private final SkillLabelRepository skillLabelRepository;
    private final LabelPermissionChecker labelPermissionChecker;

    public SkillLabelService(SkillRepository skillRepository,
                             LabelDefinitionRepository labelDefinitionRepository,
                             SkillLabelRepository skillLabelRepository,
                             LabelPermissionChecker labelPermissionChecker,
                             @Value("${skillhub.label.max-per-skill:10}") int maxLabelsPerSkill) {
        this.skillRepository = skillRepository;
        this.labelDefinitionRepository = labelDefinitionRepository;
        this.skillLabelRepository = skillLabelRepository;
        this.labelPermissionChecker = labelPermissionChecker;
        this.maxLabelsPerSkill = requirePositive(maxLabelsPerSkill, "skillhub.label.max-per-skill");
    }

    public List<SkillLabel> listSkillLabels(Long skillId) {
        return skillLabelRepository.findBySkillId(skillId);
    }

    public List<SkillLabel> listSkillLabelsBySkillIds(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return skillLabelRepository.findBySkillIdIn(skillIds);
    }

    public List<SkillLabel> listByLabelId(Long labelId) {
        return skillLabelRepository.findByLabelId(labelId);
    }

    @Transactional
    public SkillLabel attachLabel(Long skillId,
                                  String labelSlug,
                                  String operatorId,
                                  Map<Long, NamespaceRole> userNamespaceRoles,
                                  Set<String> platformRoles) {
        Skill skill = findSkill(skillId);
        LabelDefinition labelDefinition = findLabel(labelSlug);
        requireSkillLabelPermission(skill, labelDefinition, operatorId, userNamespaceRoles, platformRoles);

        List<SkillLabel> existingLabels = skillLabelRepository.findBySkillId(skillId);
        if (existingLabels.size() >= maxLabelsPerSkill) {
            throw new DomainBadRequestException("label.skill.too_many", skillId, maxLabelsPerSkill);
        }
        return skillLabelRepository.findBySkillIdAndLabelId(skillId, labelDefinition.getId())
                .orElseGet(() -> skillLabelRepository.save(new SkillLabel(skillId, labelDefinition.getId(), operatorId)));
    }

    @Transactional
    public void detachLabel(Long skillId,
                            String labelSlug,
                            String operatorId,
                            Map<Long, NamespaceRole> userNamespaceRoles,
                            Set<String> platformRoles) {
        Skill skill = findSkill(skillId);
        LabelDefinition labelDefinition = findLabel(labelSlug);
        requireSkillLabelPermission(skill, labelDefinition, operatorId, userNamespaceRoles, platformRoles);

        SkillLabel skillLabel = skillLabelRepository.findBySkillIdAndLabelId(skillId, labelDefinition.getId())
                .orElseThrow(() -> new DomainBadRequestException("label.skill.not_found", skillId, labelSlug));
        skillLabelRepository.delete(skillLabel);
    }

    /**
     * System-level auto-tagging entry point used by the LLM labeling pipeline.
     *
     * <p>Unlike {@link #attachLabel}, this bypasses {@link LabelPermissionChecker} and does not
     * require the skill to resolve — it is called with a skill id that is already known to exist.
     * Unknown or malformed slugs are skipped rather than rejected so a bad model output can never
     * fail the tagging task; the caller (background consumer) simply persists whatever is valid.
     *
     * @return number of labels actually attached
     */
    @Transactional
    public int autoTag(Long skillId, List<String> labelSlugs, String operatorId) {
        if (labelSlugs == null || labelSlugs.isEmpty()) {
            return 0;
        }
        List<SkillLabel> existing = skillLabelRepository.findBySkillId(skillId);
        int added = 0;
        for (String slug : labelSlugs) {
            if (slug == null || slug.isBlank()) {
                continue;
            }
            if (existing.size() + added >= maxLabelsPerSkill) {
                break;
            }
            String normalizedSlug = slug.trim().toLowerCase(Locale.ROOT);
            LabelDefinition definition = labelDefinitionRepository.findBySlugIgnoreCase(normalizedSlug).orElse(null);
            if (definition == null) {
                log.debug("Skipping unknown label slug during auto-tag: skillId={}, slug={}", skillId, slug);
                continue;
            }
            boolean alreadyAttached = existing.stream()
                    .anyMatch(label -> label.getLabelId().equals(definition.getId()));
            if (alreadyAttached) {
                continue;
            }
            skillLabelRepository.save(new SkillLabel(skillId, definition.getId(), operatorId));
            added++;
        }
        log.info("Auto-tagged skill: skillId={}, attached={}", skillId, added);
        return added;
    }

    private Skill findSkill(Long skillId) {
        return skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.notFound", skillId));
    }

    private LabelDefinition findLabel(String labelSlug) {
        String normalizedSlug = LabelSlugValidator.normalize(labelSlug);
        return labelDefinitionRepository.findBySlugIgnoreCase(normalizedSlug)
                .orElseThrow(() -> new DomainBadRequestException("label.not_found", normalizedSlug));
    }

    private void requireSkillLabelPermission(Skill skill,
                                             LabelDefinition labelDefinition,
                                             String operatorId,
                                             Map<Long, NamespaceRole> userNamespaceRoles,
                                             Set<String> platformRoles) {
        if (!labelPermissionChecker.canManageSkillLabel(skill, labelDefinition, operatorId, userNamespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("label.skill.no_permission");
        }
    }

    private int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }
}
