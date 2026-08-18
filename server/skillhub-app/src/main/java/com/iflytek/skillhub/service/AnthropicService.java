package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.config.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Low-level client for the Anthropic Messages API.
 * <p>
 * Uses {@link java.net.http.HttpClient} (same stack as
 * {@code BuiltinSkillRemotePackageDownloader}) for consistency.
 */
@Service
public class AnthropicService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AnthropicProperties properties;
    private final HttpClient httpClient;

    public AnthropicService(AnthropicProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Send a single message to the Anthropic API.
     *
     * @param systemPrompt the system prompt (skill instructions)
     * @param userMessage  the user's task description
     * @param maxTokens    max output tokens
     * @return the model's response
     */
    public AnthropicMessageResponse sendMessage(String systemPrompt, String userMessage, int maxTokens)
            throws IOException, InterruptedException {
        return sendMessageWithRetry(systemPrompt, userMessage, maxTokens, 0);
    }

    /**
     * Send with automatic retry on transient failures.
     */
    public AnthropicMessageResponse sendMessageWithRetry(
            String systemPrompt, String userMessage, int maxTokens, int attempt)
            throws IOException, InterruptedException {
        return sendMessageWithRetry(systemPrompt, userMessage, maxTokens, attempt, null);
    }

    /**
     * Send with automatic retry on transient failures and an optional model override.
     * The override lets judge/verification calls use a fast non-reasoning model while
     * skill execution keeps the main {@code model}.
     */
    public AnthropicMessageResponse sendMessageWithRetry(
            String systemPrompt, String userMessage, int maxTokens, int attempt, String modelOverride)
            throws IOException, InterruptedException {
        return sendMessageWithRetry(systemPrompt, userMessage, maxTokens, attempt, modelOverride, null, null);
    }

    /**
     * Send with automatic retry and optional per-call model / base URL / API key
     * overrides. The base URL and key overrides let a single comparison run target
     * an alternate Anthropic-compatible provider (e.g. GLM or a local vLLM endpoint)
     * without changing the deployment default.
     */
    public AnthropicMessageResponse sendMessageWithRetry(
            String systemPrompt, String userMessage, int maxTokens, int attempt, String modelOverride,
            String baseUrlOverride, String apiKeyOverride)
            throws IOException, InterruptedException {

        Instant start = Instant.now();

        String baseUrl = firstNonBlank(baseUrlOverride, properties.getBaseUrl());
        String apiKey = firstNonBlank(apiKeyOverride, properties.getApiKey());

        String requestBody = buildRequestBody(systemPrompt, userMessage, maxTokens, modelOverride,
                baseUrlOverride == null || baseUrlOverride.isBlank());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (attempt < properties.getMaxRetries()) {
                log.warn("Anthropic API call failed (attempt {}), retrying: {}", attempt + 1, e.getMessage());
                return sendMessageWithRetry(systemPrompt, userMessage, maxTokens, attempt + 1,
                        modelOverride, baseUrlOverride, apiKeyOverride);
            }
            throw e;
        }

        if (response.statusCode() != 200) {
            String errorMsg = "Anthropic API returned HTTP " + response.statusCode() + ": " + response.body();
            log.warn(errorMsg);

            // Retry on server errors (5xx) and rate limits (429)
            if (attempt < properties.getMaxRetries()
                    && (response.statusCode() >= 500 || response.statusCode() == 429)) {
                try {
                    Thread.sleep(1000L * (attempt + 1)); // exponential-ish backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                return sendMessageWithRetry(systemPrompt, userMessage, maxTokens, attempt + 1,
                        modelOverride, baseUrlOverride, apiKeyOverride);
            }

            throw new IOException(errorMsg);
        }

        float latency = Duration.between(start, Instant.now()).toMillis() / 1000.0f;

        JsonNode body = objectMapper.readTree(response.body());
        String content = extractContent(body);
        int tokensUsed = extractTokens(body);

        return new AnthropicMessageResponse(content, tokensUsed, latency);
    }

    private static String firstNonBlank(String override, String fallback) {
        return override != null && !override.isBlank() ? override : fallback;
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Send a lightweight verification request to check if a skill was actually applied.
     */
    public AnthropicMessageResponse sendVerification(String skillOutput, String skillName, String approach)
            throws IOException, InterruptedException {

        String systemPrompt = "You are a verification tool. Answer only YES or NO followed by a one-sentence reason.";

        // Truncate output to avoid token waste
        String truncated = skillOutput.length() > 2000
                ? skillOutput.substring(0, 1000) + "\n...(truncated)...\n" + skillOutput.substring(skillOutput.length() - 1000)
                : skillOutput;

        String userMessage = String.format(
                "An AI was instructed to use skill '%s' (approach: %s) to complete a task.%n%n" +
                "Output produced:%n%s%n%n" +
                "Did the AI actually apply the skill's methodology in the output? Answer YES or NO with one sentence evidence.",
                skillName, approach, truncated);

        // Judge/verify tasks are deterministic — use the judge model (a fast
        // non-reasoning model) when configured. Reasoning models burn the token
        // budget on a "thinking" block and can return blank; use a larger budget
        // and retry once blank so the verdict text has room to arrive.
        int maxTokens = 1024;
        String judgeModel = properties.getJudgeModel();
        AnthropicMessageResponse response =
                sendMessageWithRetry(systemPrompt, userMessage, maxTokens, 0, judgeModel);
        for (int attempt = 0; attempt < 2 && (response.content() == null || response.content().isBlank()); attempt++) {
            response = sendMessageWithRetry(systemPrompt, userMessage, maxTokens, 0, judgeModel);
        }
        return response;
    }

    /**
     * Check if the API key is configured and the service is usable.
     */
    public boolean isAvailable() {
        return properties.isApiKeyConfigured();
    }

    private String buildRequestBody(String systemPrompt, String userMessage, int maxTokens, String modelOverride,
                                    boolean disableThinking) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelOverride != null && !modelOverride.isBlank()
                ? modelOverride : properties.getModel());
        root.put("max_tokens", maxTokens);

        // Explicitly disable thinking for the default (DeepSeek) provider so the model
        // answers directly instead of emitting a "thinking" block that burns the token
        // budget and can return blank. Override providers (GLM / local vLLM) may not
        // recognise the field, so it is only sent for the default target.
        if (disableThinking) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "disabled");
        }

        ArrayNode system = root.putArray("system");
        ObjectNode systemText = system.addObject();
        systemText.put("type", "text");
        systemText.put("text", systemPrompt);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        return root.toString();
    }

    private String extractContent(JsonNode body) {
        JsonNode content = body.get("content");
        if (content == null || !content.isArray() || content.size() == 0) {
            return "";
        }
        // Anthropic returns content as an array of blocks. Reasoning models emit a
        // "thinking" block before one or more "text" blocks; concatenate every text
        // block (skipping non-text) so a multi-block answer isn't truncated to just
        // the first fragment.
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.get("type").asText())) {
                String text = block.get("text").asText();
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private int extractTokens(JsonNode body) {
        JsonNode usage = body.get("usage");
        if (usage == null) {
            return 0;
        }
        JsonNode outputTokens = usage.get("output_tokens");
        return outputTokens != null ? outputTokens.asInt() : 0;
    }

    /**
     * Response from the Anthropic Messages API.
     */
    public record AnthropicMessageResponse(
            String content,
            int tokensUsed,
            float latencySeconds
    ) {}
}
