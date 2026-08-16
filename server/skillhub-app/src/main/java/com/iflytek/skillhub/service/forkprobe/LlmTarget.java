package com.iflytek.skillhub.service.forkprobe;

/**
 * Resolved target for a single skill execution: an optional per-run override of
 * the LLM provider (base URL + API key) and model. {@code null} fields mean
 * "use the executor's default". A target with all fields blank is equivalent to
 * no override.
 */
record LlmTarget(String baseUrl, String apiKey, String model) {

    boolean hasAny() {
        return isSet(baseUrl) || isSet(apiKey) || isSet(model);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
