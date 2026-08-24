package com.iflytek.skillhub.service.forkprobe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sandbox context preamble must reach every executor's prompt, so agents
 * know they run inside SkillHub's ForkProbe comparison and can resolve
 * platform-related tasks ("介绍一下这个skillhub网站") correctly.
 */
class ForkprobeAgentContextTest {

    @Test
    void dockerSandboxPromptCarriesContext() {
        String prompt = DockerSandboxSkillExecutor.buildTaskPrompt("## 方法论", "介绍一下这个skillhub网站", "demo-skill");
        assertTrue(prompt.startsWith(ForkprobeAgentContext.SANDBOX_CONTEXT));
        assertTrue(prompt.contains("ForkProbe"));
        assertTrue(prompt.contains("SkillHub 是一个 AI Agent 技能"));
        assertTrue(prompt.contains("介绍一下这个skillhub网站"));
    }

    @Test
    void contextDescribesRoleAndPurpose() {
        String ctx = ForkprobeAgentContext.SANDBOX_CONTEXT;
        assertTrue(ctx.contains("基准参照"), "role: baseline + candidates");
        assertTrue(ctx.contains("并排展示"), "purpose: side-by-side comparison");
        assertTrue(ctx.contains("不要与其他同名网站混淆"), "disambiguation for SkillHub mentions");
        assertTrue(ctx.contains("自包含"), "output must be self-contained — readers see no tool calls");
        assertTrue(ctx.contains("过程性描述"), "no mid-process narration like '前面两组检索'");
    }
}
