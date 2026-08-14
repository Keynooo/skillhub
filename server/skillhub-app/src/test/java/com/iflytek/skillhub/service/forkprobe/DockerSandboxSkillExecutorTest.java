package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.service.AnthropicService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

class DockerSandboxSkillExecutorTest {

    private DockerSandboxSkillExecutor newExecutor(ForkprobeExecutorProperties props) {
        AnthropicProperties anthropicProps = new AnthropicProperties();
        anthropicProps.setBaseUrl("https://api.deepseek.com/anthropic");
        anthropicProps.setApiKey("test-key");
        anthropicProps.setModel("deepseek-v4-pro");
        AnthropicService anthropicService = new AnthropicService(anthropicProps);
        return new DockerSandboxSkillExecutor(anthropicService, anthropicProps, props, new Semaphore(4));
    }

    @Test
    void buildDockerCommandIncludesAllIsolationFlags() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand(Path.of("/tmp/skill"));

        assertTrue(cmd.contains("--rm"), "container must auto-remove");
        assertTrue(cmd.contains("-i"), "must attach stdin so the prompt reaches claude -p");
        assertTrue(cmd.contains("--read-only"), "root fs must be read-only");
        assertTrue(cmd.contains("--cap-drop=ALL"), "must drop all capabilities");
        assertTrue(cmd.contains("--security-opt=no-new-privileges"), "must disable privilege escalation");
        assertTrue(cmd.contains("--network"), "network flag present");
        assertTrue(cmd.contains("--memory"), "memory limit present");
        assertTrue(cmd.contains("--cpus"), "cpus limit present");
        assertTrue(cmd.contains("--pids-limit"), "pids limit present");

        int userIdx = cmd.indexOf("--user");
        assertTrue(userIdx >= 0 && userIdx + 1 < cmd.size(), "user flag present");
        assertEquals("65534:65534", cmd.get(userIdx + 1), "must run as nobody");

        int tmpfsIdx = cmd.indexOf("--tmpfs");
        assertTrue(tmpfsIdx >= 0 && tmpfsIdx + 1 < cmd.size(), "tmpfs flag present");
        assertTrue(cmd.get(tmpfsIdx + 1).startsWith("/tmp:"), "tmpfs /tmp present");
    }

    @Test
    void buildDockerCommandMountsSkillReadOnly() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand(Path.of("/tmp/skillhub-forkprobe-123/skill"));

        int volIdx = cmd.indexOf("-v");
        assertTrue(volIdx >= 0 && volIdx + 1 < cmd.size(), "volume mount present");
        String mount = cmd.get(volIdx + 1);
        assertTrue(mount.endsWith(":/skill:ro"), "skill must mount read-only, got: " + mount);
    }

    @Test
    void buildDockerCommandPassesAnthropicEnv() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand(Path.of("/tmp/skill"));

        assertTrue(cmd.contains("ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic"),
                "base URL env passed");
        assertTrue(cmd.contains("ANTHROPIC_API_KEY=test-key"), "api key env passed");
        assertTrue(cmd.contains("ANTHROPIC_MODEL=deepseek-v4-pro"), "model env passed");
    }

    @Test
    void buildDockerCommandOmitsBlankAnthropicEnv() {
        AnthropicProperties anthropicProps = new AnthropicProperties(); // blank api key / default base url
        AnthropicService anthropicService = new AnthropicService(anthropicProps);
        DockerSandboxSkillExecutor executor = new DockerSandboxSkillExecutor(
                anthropicService, anthropicProps, new ForkprobeExecutorProperties(), new Semaphore(4));

        List<String> cmd = executor.buildDockerCommand(Path.of("/tmp/skill"));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("ANTHROPIC_API_KEY=")),
                "blank api key must not be passed");
    }
}
