// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SiemPayloadFormatterTest {

    ObjectMapper mapper = new ObjectMapper();
    SiemPayloadFormatter formatter = new SiemPayloadFormatter(mapper);

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void splunkDefaultsToHec() throws Exception {
        String json = formatter.format(
                "splunk", null, 7L, ACTOR, false, "access.granted",
                "document", DOC, Map.of("k", "v"), Instant.parse("2026-01-01T00:00:00Z"));
        JsonNode root = mapper.readTree(json);
        assertThat(root.path("sourcetype").asText()).isEqualTo("socle:audit");
        assertThat(root.path("source").asText()).isEqualTo("socle");
        assertThat(root.path("event").path("audit_event_id").asLong()).isEqualTo(7L);
        assertThat(root.path("fields").path("event_type").asText()).isEqualTo("access.granted");
    }

    @Test
    void explicitFormatOverridesProviderDefault() throws Exception {
        String json = formatter.format(
                "splunk", "json", 1L, ACTOR, false, "document.created",
                "document", DOC, Map.of(), Instant.now());
        JsonNode root = mapper.readTree(json);
        assertThat(root.has("sourcetype")).isFalse();
        assertThat(root.path("action").asText()).isEqualTo("document.created");
    }

    @Test
    void datadogEnvelopeIsArray() throws Exception {
        String json = formatter.format(
                "datadog", null, 2L, ACTOR, true, "approval.escalated",
                "document", DOC, Map.of(), Instant.now());
        JsonNode root = mapper.readTree(json);
        assertThat(root.isArray()).isTrue();
        assertThat(root.get(0).path("ddsource").asText()).isEqualTo("socle");
        assertThat(root.get(0).path("attributes").path("actor_is_system").asBoolean()).isTrue();
    }
}
