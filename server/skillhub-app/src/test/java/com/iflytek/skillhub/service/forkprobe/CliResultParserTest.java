package com.iflytek.skillhub.service.forkprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link CliResultParser}: stdout polluted by CLI
 * warning prefixes and background-query envelopes must still yield the run's
 * result envelope instead of leaking raw JSON into comparison outputs.
 */
class CliResultParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesCleanSingleEnvelope() {
        String stdout = "{\"type\":\"result\",\"result\":\"干净的结果\",\"is_error\":false,"
                + "\"usage\":{\"output_tokens\":42}}";
        JsonNode root = CliResultParser.extractResultEnvelope(MAPPER, stdout);
        assertNotNull(root);
        assertEquals("干净的结果", root.path("result").asText());
        assertEquals(42, root.path("usage").path("output_tokens").asInt());
    }

    @Test
    void stripsWarningPrefixAndPicksLastResultEnvelope() {
        // Real-world shape observed in production: a "[claude-code:unrecognized_model]"
        // warning prefix glued to a session-title envelope, followed by the actual
        // result envelope — all in one stdout blob.
        String stdout = "[claude-code:unrecognized_model] "
                + "{\"model\":\"deepseek-v4-flash\",\"query_source\":\"generate_session_title\"} "
                + "{\"is_error\":false,\"duration_api_ms\":4137,\"num_turns\":1,"
                + "\"result\":\"The actual answer text\",\"type\":\"result\","
                + "\"usage\":{\"output_tokens\":323}}";
        JsonNode root = CliResultParser.extractResultEnvelope(MAPPER, stdout);
        assertNotNull(root);
        assertEquals("The actual answer text", root.path("result").asText());
        assertEquals(323, root.path("usage").path("output_tokens").asInt());
    }

    @Test
    void warningPrefixOnOwnLineAlsoHandled() {
        String stdout = "[claude-code:unrecognized_model]\n"
                + "{\"type\":\"result\",\"result\":\"ok\",\"usage\":{\"output_tokens\":1}}";
        JsonNode root = CliResultParser.extractResultEnvelope(MAPPER, stdout);
        assertNotNull(root);
        assertEquals("ok", root.path("result").asText());
    }

    @Test
    void returnsNullForNonJsonStdout() {
        assertNull(CliResultParser.extractResultEnvelope(MAPPER, "fatal: something broke"));
    }

    @Test
    void stripWarningPrefixesKeepsFollowingJson() {
        String cleaned = CliResultParser.stripWarningPrefixes(
                "[claude-code:unrecognized_model] {\"a\":1}");
        assertEquals("{\"a\":1}", cleaned);
    }
}
