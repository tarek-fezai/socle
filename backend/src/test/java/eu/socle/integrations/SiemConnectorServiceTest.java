// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.SiemPayloadFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiemConnectorServiceTest {

    static final UUID ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<SiemHttpClient.Result> httpResult = new AtomicReference<>();
    SiemConnectorService service;

    @BeforeEach
    void setUp() {
        SiemHttpClient client = (url, body, headers) -> httpResult.get();
        service = new SiemConnectorService(
                jdbc, mapper, new SiemPayloadFormatter(mapper), client,
                mock(eu.socle.audit.AuditService.class),
                mock(eu.socle.audit.AuditActorResolver.class));
    }

    @Test
    void create_storesConfigAndReturnsRedactedSecrets() {
        when(jdbc.update(contains("INSERT INTO siem_connectors"), any(), any(), any()))
                .thenReturn(1);
        stubGetAfterWrite("{\"endpoint\":\"https://hec.example/collector\",\"format\":\"hec\",\"hec_token\":\"super-secret-token-value\"}");

        var view = service.create("splunk", Map.of(
                "endpoint", "https://hec.example/collector",
                "format", "hec",
                "hec_token", "super-secret-token-value"
        ));

        assertThat(view.status()).isEqualTo("disconnected");
        assertThat(view.config()).containsEntry("endpoint", "https://hec.example/collector");
        assertThat(view.config()).containsKey("hec_token_prefix");
        assertThat(view.config()).doesNotContainKey("hec_token");
        assertThat(String.valueOf(view.config().get("hec_token_prefix"))).doesNotContain("super-secret");

        ArgumentCaptor<Object> configCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO siem_connectors"), any(), eq("splunk"), configCap.capture());
        assertThat(String.valueOf(configCap.getValue())).contains("super-secret-token-value");
    }

    @Test
    void get_neverReturnsPlainSecret() {
        stubGetAfterWrite("{\"endpoint\":\"https://x\",\"hec_token\":\"abc123456789\"}");
        var view = service.get(ID);
        assertThat(view.config()).doesNotContainKey("hec_token");
        assertThat(view.config()).containsKey("hec_token_prefix");
    }

    @Test
    void test_success_doesNotUpdateStatus() {
        when(jdbc.query(contains("FROM siem_connectors WHERE id"), any(RowMapper.class), eq(ID)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(ID);
                    when(rs.getString("provider")).thenReturn("splunk");
                    when(rs.getString("config")).thenReturn(
                            "{\"endpoint\":\"https://hec.example\",\"hec_token\":\"tok\"}");
                    when(rs.getString("status")).thenReturn("disconnected");
                    when(rs.getTimestamp("connected_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
        httpResult.set(new SiemHttpClient.Result(200, null));

        var result = service.test(ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        verify(jdbc, never()).update(contains("UPDATE siem_connectors"), any(), any(), any(), any());
    }

    @Test
    void test_failure_doesNotUpdateStatus() {
        when(jdbc.query(contains("FROM siem_connectors WHERE id"), any(RowMapper.class), eq(ID)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(ID);
                    when(rs.getString("provider")).thenReturn("splunk");
                    when(rs.getString("config")).thenReturn(
                            "{\"endpoint\":\"https://hec.example\",\"hec_token\":\"tok\"}");
                    when(rs.getString("status")).thenReturn("disconnected");
                    when(rs.getTimestamp("connected_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
        httpResult.set(new SiemHttpClient.Result(503, "HTTP 503"));

        var result = service.test(ID);

        assertThat(result.ok()).isFalse();
        verify(jdbc, never()).update(contains("UPDATE siem_connectors"), any(), any(), any(), any());
    }

    private void stubGetAfterWrite(String configJson) {
        when(jdbc.query(contains("FROM siem_connectors WHERE id"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(ID);
                    when(rs.getString("provider")).thenReturn("splunk");
                    when(rs.getString("config")).thenReturn(configJson);
                    when(rs.getString("status")).thenReturn("disconnected");
                    when(rs.getTimestamp("connected_at")).thenReturn((Timestamp) null);
                    return List.of(rm.mapRow(rs, 0));
                });
    }
}
