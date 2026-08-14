package com.iflytek.skillhub.domain.label;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LabelPermissionChecker {

    public boolean canManageDefinitions(Set<String> platformRoles) {
        return platformRoles.contains("SUPER_ADMIN");
    }

    public boolean canManageSkillLabel(Skill skill,
                                       LabelDefinition labelDefinition,
                                       String userId,
                                       Map<Long, NamespaceRole> userNamespaceRoles,
                                       Set<String> platformRoles) {
        // Skill labeling is now automated (LLM auto-tagging on publish). Manual attach/detach
        // is reserved for SUPER_ADMIN so regular owners and namespace admins can no longer
        // hand-pick labels; the unused parameters preserve the checker's call-site contract.
        return platformRoles.contains("SUPER_ADMIN");
    }
}
