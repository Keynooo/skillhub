package com.iflytek.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic Messages API integration used by the
 * forkprobe comparison sandbox.
 */
@ConfigurationProperties(prefix = "skillhub.anthropic")
public class AnthropicProperties {

    /** Anthropic API key (from ANTHROPIC_API_KEY env var). */
    private String apiKey;

    /** Model to use for comparison runs (default: haiku for cost efficiency). */
    private String model = "claude-haiku-4-5-20251001";

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
}
