package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.EditLockService.EditLockView;
import eu.socle.user.UserEntity;
import eu.socle.user.UserRepository;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EditLockServiceTest {

    static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock UserRepository userRepository;

    Map<UUID, Object[]> lockRows = new ConcurrentHashMap<>();
    EditLockProperties properties;
    Clock clock;
    EditLockService service;

    @BeforeEach
    void setUp() {
        properties = new EditLockProperties();
        properties.setHeartbeatSeconds(15);
        properties.setTtlSeconds(45);
        properties.validate();
        clock = Clock.fixed(T0, ZoneOffset.UTC);
        service = new EditLockService(
                jdbc, authorizationService, userSyncService, userRepository, properties, clock);

        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        when(userRepository.findById(USER_A)).thenReturn(Optional.of(user(USER_A, "Alice")));
        when(userRepository.findById(USER_B)).thenReturn(Optional.of(user(USER_B, "Bob")));

        stubJdbc();
    }

    @Test
    void acquireByA_visibleToB() {
        doNothing().when(authorizationService).requireDocumentRelation(USER_A, DOC, "editor");
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");

        EditLockView a = service.acquire(jwt(USER_A), DOC);
        assertThat(a.active()).isTrue();
        assertThat(a.heldByCurrentUser()).isTrue();
        assertThat(a.holderDisplayName()).isEqualTo("Alice");

        EditLockView b = service.status(jwt(USER_B), DOC);
        assertThat(b.active()).isTrue();
        assertThat(b.holderUserId()).isEqualTo(USER_A);
        assertThat(b.heldByCurrentUser()).isFalse();
        assertThat(b.holderDisplayName()).isEqualTo("Alice");
    }

    @Test
    void expiresAfterTtlWithoutHeartbeat() {
        doNothing().when(authorizationService).requireDocumentRelation(USER_A, DOC, "editor");
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");

        service.acquire(jwt(USER_A), DOC);

        Clock later = Clock.fixed(T0.plusSeconds(46), ZoneOffset.UTC);
        EditLockService expiredSvc = new EditLockService(
                jdbc, authorizationService, userSyncService, userRepository, properties, later);

        EditLockView b = expiredSvc.status(jwt(USER_B), DOC);
        assertThat(b.active()).isFalse();
    }

    @Test
    void releaseByA_immediatelyClearsForB() {
        doNothing().when(authorizationService).requireDocumentRelation(USER_A, DOC, "editor");
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");

        service.acquire(jwt(USER_A), DOC);
        service.release(jwt(USER_A), DOC);

        EditLockView b = service.status(jwt(USER_B), DOC);
        assertThat(b.active()).isFalse();
    }

    @Test
    void status_withoutViewer_returns403WithoutLeak() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé"))
                .when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");

        assertThatThrownBy(() -> service.status(jwt(USER_B), DOC))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), eq(DOC));
    }

    @Test
    void otherUserAcquire_doesNotStealActiveLock() {
        doNothing().when(authorizationService).requireDocumentRelation(USER_A, DOC, "editor");
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "editor");

        service.acquire(jwt(USER_A), DOC);
        EditLockView b = service.acquire(jwt(USER_B), DOC);

        assertThat(b.active()).isTrue();
        assertThat(b.holderUserId()).isEqualTo(USER_A);
        assertThat(b.heldByCurrentUser()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void stubJdbc() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(DOC))).thenAnswer(inv -> {
            Object[] row = lockRows.get(DOC);
            if (row == null) {
                return List.of();
            }
            RowMapper<Object> mapper = inv.getArgument(1);
            var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getObject("document_id")).thenReturn(DOC);
            when(rs.getObject("holder_user_id")).thenReturn(row[0]);
            when(rs.getTimestamp("acquired_at")).thenReturn(Timestamp.from((Instant) row[1]));
            when(rs.getTimestamp("heartbeat_at")).thenReturn(Timestamp.from((Instant) row[2]));
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            UUID holder = inv.getArgument(2);
            Instant acquired = ((Timestamp) inv.getArgument(3)).toInstant();
            Instant hb = ((Timestamp) inv.getArgument(4)).toInstant();
            Instant cutoff = ((Timestamp) inv.getArgument(5)).toInstant();
            Object[] existing = lockRows.get(DOC);
            if (existing != null
                    && existing[0].equals(holder)
                    && ((Instant) existing[2]).isAfter(cutoff)) {
                lockRows.put(DOC, new Object[]{holder, existing[1], hb});
            } else {
                lockRows.put(DOC, new Object[]{holder, acquired, hb});
            }
            return 1;
        });
        when(jdbc.update(anyString(), eq(DOC), any(UUID.class))).thenAnswer(inv -> {
            UUID holder = inv.getArgument(2);
            Object[] existing = lockRows.get(DOC);
            if (existing != null && existing[0].equals(holder)) {
                lockRows.remove(DOC);
                return 1;
            }
            return 0;
        });
        when(jdbc.update(anyString(), eq(DOC))).thenAnswer(inv -> {
            lockRows.remove(DOC);
            return 1;
        });
    }

    private static UserEntity user(UUID id) {
        return user(id, "U");
    }

    private static UserEntity user(UUID id, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@ex.com");
        u.setDisplayName(name);
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID userId) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
