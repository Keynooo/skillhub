package com.iflytek.skillhub.service;

import com.iflytek.skillhub.config.AnthropicProperties;
import com.iflytek.skillhub.domain.skill.SummaryTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * {@link SummaryTranslator} backed by the Anthropic-compatible LLM client.
 *
 * <p>Translations are strictly best-effort: an unconfigured key, a summary that already contains
 * Chinese, or a failed call all degrade to {@code null} so a publish is never blocked or failed by
 * localization.
 */
@Service
public class SkillSummaryTranslationService implements SummaryTranslator {

    private static final Logger log = LoggerFactory.getLogger(SkillSummaryTranslationService.class);

    /** Any Han character marks the text as already (at least partly) Chinese. */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final int MAX_TOKENS = 1024;

    private final AnthropicService anthropicService;
    private final AnthropicProperties properties;

    public SkillSummaryTranslationService(AnthropicService anthropicService, AnthropicProperties properties) {
        this.anthropicService = anthropicService;
        this.properties = properties;
    }

    @Override
    public String translateToChinese(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (CJK.matcher(text).find()) {
            return null;
        }
        if (!anthropicService.isAvailable()) {
            return null;
        }

        String systemPrompt = "You are a translator. Translate the user's English skill summary "
                + "into concise, natural Simplified Chinese. Return ONLY the Chinese translation, "
                + "no explanations, no quotes, no markdown.";
        String userMessage = text.trim();

        try {
            // Translation is a short deterministic task — prefer the judge model (a fast,
            // non-reasoning model) when configured so a reasoning model doesn't burn the token
            // budget on a "thinking" block and return blank.
            AnthropicService.AnthropicMessageResponse response = anthropicService.sendMessageWithRetry(
                    systemPrompt, userMessage, MAX_TOKENS, 0, properties.getJudgeModel());
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
}
