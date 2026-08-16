package com.iflytek.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for the Anthropic Messages API integration used by the
 * forkprobe comparison sandbox.
 */
@ConfigurationProperties(prefix = "skillhub.anthropic")
public class AnthropicProperties {

    /** Anthropic API key (from ANTHROPIC_API_KEY env var). */
    private String apiKey;

    /**
     * Named alternate LLM providers (Anthropic-protocol-compatible) selectable
     * per comparison run, keyed by a short id (e.g. {@code glm}, {@code local}).
     * Each provider overrides base URL, API key, and model for that run.
     */
    private final Map<String, Provider> providers = new HashMap<>();

    /** A single alternate LLM provider definition. */
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String model;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }
    }

    /** Model to use for comparison runs (default: haiku for cost efficiency). */
    private String model = "claude-haiku-4-5-20251001";

    /**
     * Model for deterministic judge/verification calls (forkprobe review + skill
     * verification). Empty means fall back to {@link #model}. Use a non-reasoning
     * model here so the judge returns its verdict directly instead of burning the
     * token budget on a "thinking" block.
     */
    private String judgeModel = "";

    /** Maximum output tokens per skill execution. */
    private int maxTokens = 4096;

    /** Anthropic API base URL. */
    private String baseUrl = "https://api.anthropic.com";

    /** Timeout in seconds for individual API calls. */
    private int timeoutSeconds = 120;

    /** Number of retries on transient failures. */
    private int maxRetries = 2;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    public void setJudgeModel(String judgeModel) {
        this.judgeModel = judgeModel;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        if (providers != null) {
            this.providers.clear();
            this.providers.putAll(providers);
        }
    }

    public Optional<Provider> getProvider(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(name));
    }
}
