// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiemDeliveryEnqueueServiceTest {

    static final UUID CONNECTOR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock JdbcTemplate jdbcTemplate;
    ObjectMapper objectMapper = new ObjectMapper();
    SiemDeliveryEnqueueService service;

    @BeforeEach
    void setUp() {
        service = new SiemDeliveryEnqueueService(
                jdbcTemplate, objectMapper, new SiemPayloadFormatter(objectMapper), new SimpleMeterRegistry());
    }

    @Test
    void connectedConnector_createsDeliveryWithHecPayload() throws Exception {
        stubConnected(List.of(new SiemDeliveryEnqueueService.ConnectorRow(
                CONNECTOR, "splunk",
                "{\"endpoint\":\"https://hec.example/services/collector\",\"format\":\"hec\",\"hec_token\":\"t\"}"
        )));
        when(jdbcTemplate.update(contains("siem_deliveries"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Instant at = Instant.parse("2026-03-15T10:00:00Z");
        int created = service.doEnqueue(
                42L, ACTOR, false, AuditActions.ACCESS_GRANTED,
                "document", DOC, Map.of("relation", "editor"), at);

        assertThat(created).isEqualTo(1);

        ArgumentCaptor<Object> id = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> connectorId = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> auditId = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> eventType = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> createdAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(
                contains("siem_deliveries"),
                id.capture(), connectorId.capture(), auditId.capture(),
                eventType.capture(), payload.capture(), createdAt.capture());

        assertThat(connectorId.getValue()).isEqualTo(CONNECTOR);
        assertThat(auditId.getValue()).isEqualTo(42L);
        assertThat(eventType.getValue()).isEqualTo(AuditActions.ACCESS_GRANTED);
        JsonNode root = objectMapper.readTree((String) payload.getValue());
        assertThat(root.path("sourcetype").asText()).isEqualTo("socle:audit");
        assertThat(root.path("event").path("action").asText()).isEqualTo(AuditActions.ACCESS_GRANTED);
        assertThat(root.path("event").path("audit_event_id").asLong()).isEqualTo(42L);
        assertThat(createdAt.getValue()).isInstanceOf(Timestamp.class);
    }

    @Test
    void noConnectedConnector_createsNothing() {
        stubConnected(Collections.emptyList());

        int created = service.doEnqueue(
                1L, ACTOR, false, AuditActions.DOCUMENT_CREATED,
                "document", DOC, Map.of(), Instant.now());

        assertThat(created).isEqualTo(0);
        verify(jdbcTemplate, never()).update(contains("siem_deliveries"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void queryOnlyConnected_ignoresDisconnectedAndError() {
        stubConnected(Collections.emptyList());

        service.doEnqueue(2L, ACTOR, false, AuditActions.ACCESS_GRANTED,
                "space", DOC, Map.of(), Instant.now());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("status = 'connected'");
        verify(jdbcTemplate, never()).update(contains("siem_deliveries"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void multipleConnectors_formatsOnceEach() {
        UUID c2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        stubConnected(List.of(
                new SiemDeliveryEnqueueService.ConnectorRow(CONNECTOR, "splunk",
                        "{\"endpoint\":\"https://a\",\"format\":\"hec\",\"hec_token\":\"t1\"}"),
                new SiemDeliveryEnqueueService.ConnectorRow(c2, "datadog",
                        "{\"endpoint\":\"https://b\",\"format\":\"datadog\",\"api_key\":\"k\"}")
        ));
        when(jdbcTemplate.update(contains("siem_deliveries"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int created = service.doEnqueue(
                9L, ACTOR, false, AuditActions.DOCUMENT_UPDATED,
                "document", DOC, Map.of(), Instant.now());

        assertThat(created).isEqualTo(2);
        verify(jdbcTemplate, times(2))
                .update(contains("siem_deliveries"), any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubConnected(List<SiemDeliveryEnqueueService.ConnectorRow> rows) {
        when(jdbcTemplate.query(contains("siem_connectors"), any(RowMapper.class))).thenReturn(rows);
    }
}
