// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryQueryServiceTest {

    static final UUID EP = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID D1 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock JdbcTemplate jdbc;

    @Test
    void list_filtersByStatusInSql() throws Exception {
        WebhookDeliveryQueryService svc = new WebhookDeliveryQueryService(jdbc);
        when(jdbc.queryForObject(contains("count(*)"), eq(Long.class), eq("failed")))
                .thenReturn(1L);
        when(jdbc.query(contains("ORDER BY d.created_at DESC"), any(RowMapper.class), eq("failed"), eq(50), eq(0)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(D1);
                    when(rs.getObject("endpoint_id")).thenReturn(EP);
                    when(rs.getString("endpoint_url")).thenReturn("https://hooks.example/relay");
                    when(rs.getString("event_type")).thenReturn("document.created");
                    when(rs.getString("status")).thenReturn("failed");
                    when(rs.getInt("attempt_count")).thenReturn(3);
                    when(rs.getObject("last_response_code")).thenReturn(504);
                    when(rs.getTimestamp("delivered_at")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-03-15T10:00:00Z")));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var page = svc.list(null, "failed", 0, 50);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().status()).isEqualTo("failed");
        assertThat(page.items().getFirst().endpointUrl()).contains("hooks.example");
        verify(jdbc).queryForObject(contains("AND d.status = ?"), eq(Long.class), eq("failed"));
    }

    @Test
    void list_rejectsInvalidStatus() {
        WebhookDeliveryQueryService svc = new WebhookDeliveryQueryService(jdbc);
        assertThatThrownBy(() -> svc.list(null, "boom", 0, 10))
                .isInstanceOf(ResponseStatusException.class);
    }
}
