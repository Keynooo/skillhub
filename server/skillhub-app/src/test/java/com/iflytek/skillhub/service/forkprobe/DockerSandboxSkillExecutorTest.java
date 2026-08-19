package com.iflytek.skillhub.service.forkprobe;

import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.service.AnthropicService;
import org.junit.jupiter.api.Test;

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
        List<String> cmd = executor.buildDockerCommand();

        assertFalse(cmd.contains("--rm"), "no --rm — deliverable files are docker cp'd out before docker rm");
        assertTrue(cmd.contains("--name"), "named container so files can be extracted via docker cp");
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
    void buildDockerCommandMountsOutputVolume() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand();

        int vIdx = cmd.indexOf("-v");
        assertTrue(vIdx >= 0 && vIdx + 1 < cmd.size(), "named volume flag present");
        assertEquals("skillhub-out-test:/output", cmd.get(vIdx + 1),
                "deliverable files mount at /output via a named volume");
    }

    @Test
    void buildDockerCommandUsesNamedVolumeNotBindMount() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand();

        // The only -v is a named volume for /output (source is a bare volume name, not a
        // host path) — a host bind-mount would not resolve under DooD.
        for (int i = 0; i < cmd.size(); i++) {
            if ("-v".equals(cmd.get(i)) && i + 1 < cmd.size()) {
                String spec = cmd.get(i + 1);
                assertFalse(spec.startsWith("/"), "no host bind-mount: " + spec);
                assertFalse(spec.matches("^[A-Za-z]:.*"), "no Windows bind-mount: " + spec);
            }
        }
        assertFalse(cmd.contains("--add-dir"), "no --add-dir — skill is delivered inline in the prompt");
    }

    @Test
    void buildTaskPromptEmbedsSkillContent() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        String prompt = executor.buildTaskPrompt("查天气方法论内容", "帮我查天气", "weather");

        assertTrue(prompt.contains("查天气方法论内容"), "prompt must embed the full SKILL.md methodology");
        assertTrue(prompt.contains("帮我查天气"), "prompt must embed the task");
        assertTrue(prompt.contains("weather"), "prompt must name the skill");
        assertTrue(prompt.contains("/output"), "prompt must direct deliverable files to /output");
    }

    @Test
    void buildDockerCommandPassesAnthropicEnv() {
        DockerSandboxSkillExecutor executor = newExecutor(new ForkprobeExecutorProperties());
        List<String> cmd = executor.buildDockerCommand();

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

        List<String> cmd = executor.buildDockerCommand();
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("ANTHROPIC_API_KEY=")),
                "blank api key must not be passed");
    }
}
