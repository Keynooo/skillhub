package com.iflytek.skillhub;

import com.iflytek.skillhub.bootstrap.BuiltinSkillProperties;
import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.config.ForkprobeExecutorProperties;
import com.iflytek.skillhub.config.ProfileFieldPolicyProperties;
import com.iflytek.skillhub.config.ProfileModerationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Main Spring Boot entry point for the SkillHub backend application.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        AnthropicProperties.class,
        ForkprobeExecutorProperties.class,
        BuiltinSkillProperties.class,
        ProfileModerationProperties.class,
        ProfileFieldPolicyProperties.class
})
public class SkillhubApplication {
    private static final Logger log = LoggerFactory.getLogger(SkillhubApplication.class);

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(SkillhubApplication.class, args);
    }

    /**
     * Loads a {@code .env} file (KEY=VALUE lines) into Java system properties before
     * Spring builds its Environment, so values are visible to {@code ${VAR}} placeholders
     * in application.yml without a manual {@code source ./.env} step.
     * <p>
     * An existing OS environment variable always wins (production safety); a missing
     * {@code .env} is silently ignored so the app still starts with defaults.
     */
    private static void loadDotEnv() {
        Path envFile = resolveDotEnv();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return;
        }
        int loaded = 0;
        try {
            for (String raw : Files.readAllLines(envFile)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (key.isEmpty() || System.getenv(key) != null) {
                    continue; // skip empty keys; never override a real env var
                }
                System.setProperty(key, value);
                loaded++;
            }
            log.info("Loaded {} entries from {}", loaded, envFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to load .env from {}: {}", envFile.toAbsolutePath(), e.getMessage());
        }
    }

    /** Searches the working directory and its parents (up to 5 levels) for a {@code .env} file. */
    private static Path resolveDotEnv() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
