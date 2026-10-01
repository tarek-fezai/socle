// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID NOTIF_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID NOTIF_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserSyncService userSyncService;

    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(jdbcTemplate, userSyncService, new ObjectMapper());
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
    }

    @Test
    void list_returnsOnlyCurrentUserNotifications() {
        when(jdbcTemplate.query(contains("FROM notifications n"), any(RowMapper.class), eq(USER_A), anyInt(), anyInt()))
                .thenAnswer(inv -> List.of(mapNotif(inv.getArgument(1), NOTIF_A, USER_A, null)));
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM notifications n"), eq(Long.class), eq(USER_A)))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(contains("read_at IS NULL"), eq(Long.class), eq(USER_A)))
                .thenReturn(1L);

        var page = service.list(jwt(USER_A), false, 0, 50);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo(NOTIF_A);
        assertThat(page.items().getFirst().type()).isEqualTo("approval_chain_exhausted");
        assertThat(page.items().getFirst().documentTitle()).isEqualTo("Politique accès");
        assertThat(page.unreadCount()).isEqualTo(1);
        verify(jdbcTemplate).query(contains("FROM notifications n"), any(RowMapper.class), eq(USER_A), eq(50), eq(0));
    }

    @Test
    void list_unreadOnly_filtersReadAtNull() {
        when(jdbcTemplate.query(contains("AND n.read_at IS NULL"), any(RowMapper.class), eq(USER_A), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(USER_A)))
                .thenReturn(0L);

        var page = service.list(jwt(USER_A), true, 0, 20);

        assertThat(page.items()).isEmpty();
        verify(jdbcTemplate).query(contains("AND n.read_at IS NULL"), any(RowMapper.class), eq(USER_A), eq(20), eq(0));
    }

    @Test
    void markRead_otherUsersNotification_returns404() {
        when(jdbcTemplate.update(contains("UPDATE notifications"), eq(NOTIF_B), eq(USER_A)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("WHERE n.id = ? AND n.user_id = ?"), any(RowMapper.class), eq(NOTIF_B), eq(USER_A)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.markRead(jwt(USER_A), NOTIF_B))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void markRead_ownNotification_setsReadAt() {
        when(jdbcTemplate.update(contains("UPDATE notifications"), eq(NOTIF_A), eq(USER_A)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("WHERE n.id = ? AND n.user_id = ?"), any(RowMapper.class), eq(NOTIF_A), eq(USER_A)))
                .thenAnswer(inv -> List.of(mapNotif(
                        inv.getArgument(1),
                        NOTIF_A,
                        USER_A,
                        Timestamp.from(Instant.parse("2026-09-28T18:00:00Z")))));

        var view = service.markRead(jwt(USER_A), NOTIF_A);

        assertThat(view.id()).isEqualTo(NOTIF_A);
        assertThat(view.readAt()).isEqualTo("2026-09-28T18:00:00Z");
        verify(jdbcTemplate).update(contains("UPDATE notifications"), eq(NOTIF_A), eq(USER_A));
    }

    @SuppressWarnings("unchecked")
    private NotificationService.NotificationView mapNotif(
            RowMapper<?> mapper,
            UUID id,
            UUID ignoredUser,
            Timestamp readAt
    ) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getString("type")).thenReturn("approval_chain_exhausted");
        when(rs.getString("payload_json")).thenReturn(
                "{\"document_id\":\"" + DOC + "\",\"approval_request_id\":\"" + NOTIF_A
                        + "\",\"steps_traversed\":2}");
        when(rs.getString("document_title")).thenReturn("Politique accès");
        when(rs.getTimestamp("read_at")).thenReturn(readAt);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-28T12:00:00Z")));
        return (NotificationService.NotificationView) mapper.mapRow(rs, 0);
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        u.setSystemAccount(false);
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
