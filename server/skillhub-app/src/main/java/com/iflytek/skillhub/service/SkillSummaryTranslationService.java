package com.iflytek.skillhub.service;

import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.domain.skill.SummaryTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SummaryTranslator} backed by the Anthropic-compatible LLM client.
 *
 * <p>Translations are strictly best-effort: a summary that already contains Chinese, an
 * unconfigured LLM target, or a failed call all degrade to {@code null} so a publish is never
 * blocked or failed by localization.
 *
 * <p>The LLM target is chosen by {@code skillhub.summary-translation.provider}: the literal
 * {@code default} (or blank) uses the deployment default Anthropic key, while any other value
 * selects a named {@code skillhub.anthropic.providers.*} entry (e.g. {@code glm}). This mirrors how
 * forkprobe resolves its per-run provider, so translation works on deployments that only configure
 * a provider (GLM/local) and never set a top-level {@code ANTHROPIC_API_KEY}.
 */
@Service
public class SkillSummaryTranslationService implements SummaryTranslator {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryTranslationService.class);

    /** Any Han character marks the text as already (at least partly) Chinese. */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final int MAX_TOKENS = 1024;

    private final AnthropicService anthropicService;
    private final AnthropicProperties properties;
    private final String providerName;

    public SkillSummaryTranslationService(AnthropicService anthropicService,
                                          AnthropicProperties properties,
                                          @Value("${skillhub.summary-translation.provider:glm}") String providerName) {
        this.anthropicService = anthropicService;
        this.properties = properties;
        this.providerName = providerName;
    }

    @Override
    public String translateToChinese(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (isPredominantlyChinese(text)) {
            return null;
        }

        Target target = resolveTarget();
        if (target == null) {
            return null;
        }

        String systemPrompt = "You are a translator. Translate the user's English skill summary "
                + "into concise, natural Simplified Chinese. Return ONLY the Chinese translation, "
                + "no explanations, no quotes, no markdown.";

        try {
            AnthropicService.AnthropicMessageResponse response = anthropicService.sendMessageWithRetry(
                    systemPrompt, text.trim(), MAX_TOKENS, 0,
                    target.model(), target.baseUrl(), target.apiKey());
            String content = response.content();
            if (content == null || content.isBlank()) {
                log.warn("Summary translation returned blank content");
                return null;
            }
            return content.trim();
        } catch (Exception e) {
            log.warn("Failed to translate skill summary (best-effort): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns true when the summary is already predominantly Chinese and therefore needs no
     * translation. A bilingual summary (mostly English with a few Chinese trigger phrases) still
     * has more Latin letters than Han characters, so it is translated into a clean Chinese
     * summary rather than skipped.
     */
    private boolean isPredominantlyChinese(String text) {
        Matcher matcher = CJK.matcher(text);
        int cjk = 0;
        while (matcher.find()) {
            cjk++;
        }
        int latin = 0;
        for (char c : text.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        return cjk > latin;
    }

    private Target resolveTarget() {
        if (providerName == null || providerName.isBlank() || "default".equalsIgnoreCase(providerName)) {
            if (!anthropicService.isAvailable()) {
                return null;
            }
            return new Target(properties.getJudgeModel(), properties.getBaseUrl(), properties.getApiKey());
        }

        return properties.getProvider(providerName)
                .filter(AnthropicProperties.Provider::isConfigured)
                .map(provider -> new Target(provider.getModel(), provider.getBaseUrl(), provider.getApiKey()))
                .orElse(null);
    }

    private record Target(String model, String baseUrl, String apiKey) {
    }
}
