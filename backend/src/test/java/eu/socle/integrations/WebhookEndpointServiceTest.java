// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEndpointServiceTest {

    static final UUID ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock JdbcTemplate jdbc;

    @Test
    void create_returnsSecretOnce_andStoresHashOnly() throws Exception {
        WebhookEndpointService svc = new WebhookEndpointService(jdbc, mock(eu.socle.audit.AuditService.class), mock(eu.socle.audit.AuditActorResolver.class));

        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            PreparedStatementCreator psc = inv.getArgument(0);
            Connection con = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            Array arr = mock(Array.class);
            when(con.prepareStatement(any())).thenReturn(ps);
            when(con.createArrayOf(eq("text"), any())).thenReturn(arr);
            psc.createPreparedStatement(con);
            ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
            verify(ps).setString(eq(3), hashCap.capture());
            assertThat(hashCap.getValue()).hasSize(64); // sha256 hex
            assertThat(hashCap.getValue()).doesNotStartWith("whsec_");
            return 1;
        });

        when(jdbc.query(contains("FROM webhook_endpoints WHERE id"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    Array arr = mock(Array.class);
                    when(arr.getArray()).thenReturn(new String[]{"document.published"});
                    when(rs.getObject("id", UUID.class)).thenReturn(ID);
                    when(rs.getString("url")).thenReturn("https://hooks.example/socle");
                    when(rs.getArray("subscribed_events")).thenReturn(arr);
                    when(rs.getString("status")).thenReturn("active");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(rm.mapRow(rs, 0));
                });

        var created = svc.create("https://hooks.example/socle", List.of("document.published"), "active");

        assertThat(created.secret()).startsWith("whsec_");
        assertThat(created.secretPrefix()).isNotBlank();
        assertThat(created.endpoint().url()).contains("hooks.example");
        // La vue GET n'expose pas de champ secret
        assertThat(created.endpoint().toString()).doesNotContain(created.secret());
    }

    @Test
    void get_hasNoSecretField() throws Exception {
        WebhookEndpointService svc = new WebhookEndpointService(jdbc, mock(eu.socle.audit.AuditService.class), mock(eu.socle.audit.AuditActorResolver.class));
        when(jdbc.query(contains("FROM webhook_endpoints WHERE id"), any(RowMapper.class), eq(ID)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    Array arr = mock(Array.class);
                    when(arr.getArray()).thenReturn(new String[]{"tag.added"});
                    when(rs.getObject("id", UUID.class)).thenReturn(ID);
                    when(rs.getString("url")).thenReturn("https://hooks.example/x");
                    when(rs.getArray("subscribed_events")).thenReturn(arr);
                    when(rs.getString("status")).thenReturn("active");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(rm.mapRow(rs, 0));
                });

        var view = svc.get(ID);
        assertThat(view.url()).isEqualTo("https://hooks.example/x");
        // record EndpointView n'a pas de secret — garanti par le type
        assertThat(view.getClass().getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("secret", "secretHash", "secret_hash");
    }
}
