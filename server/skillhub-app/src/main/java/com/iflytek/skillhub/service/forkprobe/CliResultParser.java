package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * Extracts the final result envelope from Claude CLI stdout.
 * <p>
 * Newer CLI versions may interleave non-result content in stdout: warning
 * prefixes like {@code [claude-code:unrecognized_model]} and extra JSON
 * envelopes from background queries (e.g. session-title generation) ahead of
 * the actual result envelope. Jackson's single-value {@code readTree} silently
 * returns the FIRST value when several are concatenated, so the title envelope
 * shadowed the real result — and before this parser existed, a parse failure
 * dumped the whole raw blob into comparison outputs (0 tokens). This parser
 * strips warning prefixes, scans every top-level JSON value, and keeps the
 * LAST result envelope in the stream.
 */
final class CliResultParser {

    /** CLI warning prefixes such as "[claude-code:unrecognized_model] ". */
    private static final Pattern WARNING_PREFIX =
            Pattern.compile("\\[claude-code:[a-z0-9_-]+\\]\\s*");

    private CliResultParser() {
    }

    /**
     * Parse stdout into the run's result envelope. Returns {@code null} when
     * no JSON envelope is present at all (caller falls back to raw text).
     */
    static JsonNode extractResultEnvelope(ObjectMapper mapper, String stdout) {
        String cleaned = stripWarningPrefixes(stdout);
        JsonNode last = null;
        try (var parser = mapper.getFactory().createParser(cleaned)) {
            while (true) {
                JsonNode node;
                try {
                    if (parser.nextToken() == null) {
                        break;
                    }
                    node = mapper.readTree(parser);
                } catch (Exception e) {
                    break; // trailing non-JSON garbage after the last value
                }
                if (node != null && node.isObject() && isResultEnvelope(node)) {
                    last = node;
                }
            }
        } catch (Exception ignored) {
        }
        return last;
    }

    /** A run result (or error) envelope — background-query envelopes have none of these. */
    private static boolean isResultEnvelope(JsonNode node) {
        return node.has("result") || node.has("is_error")
                || "result".equals(node.path("type").asText());
    }

    /** Remove "[claude-code:…]" warning prefixes, keeping the JSON that follows them. */
    static String stripWarningPrefixes(String stdout) {
        return WARNING_PREFIX.matcher(stdout).replaceAll("");
    }
}
