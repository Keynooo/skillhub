package com.iflytek.skillhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

/**
 * Wiring for forkprobe's sandbox execution backend.
 */
@Configuration
public class ForkprobeConfig {

    /**
     * Global semaphore capping the number of simultaneously running sandbox
     * containers across <em>all</em> comparison runs. Unlike the per-comparison
     * {@code Semaphore(3)} inside {@code ForkprobeComparisonService}, this scope
     * spans every user so total container resource usage stays bounded.
     */
    @Bean
    public Semaphore forkprobeSandboxSemaphore(ForkprobeExecutorProperties properties) {
        return new Semaphore(Math.max(1, properties.getSandboxMaxConcurrency()));
    }
}
