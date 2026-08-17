package com.iflytek.skillhub.domain.skill;

/**
 * Translates an English skill summary into Chinese so the detail page can show a localized summary
 * above the original text.
 *
 * <p>Implementations are best-effort: they return {@code null} when the text does not need
 * translation (e.g. it already contains Chinese), when no model is configured, or when the call
 * fails. Callers persist the returned value as {@code null} so the UI simply hides the localized
 * block instead of surfacing an error.
 */
public interface SummaryTranslator {

    /**
     * Translate a summary to Chinese if needed.
     *
     * @param text the original summary (may be {@code null} or blank)
     * @return the Chinese translation, or {@code null} when no translation is produced
     */
    String translateToChinese(String text);
}
