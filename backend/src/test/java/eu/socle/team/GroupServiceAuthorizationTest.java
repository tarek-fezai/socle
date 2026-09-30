package eu.socle.team;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.team.GroupDtos.AddMemberRequest;
import eu.socle.team.GroupDtos.CreateGroupRequest;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Point 6 — protection explicite : non-créateur ne peut pas gérer les membres (403).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupServiceAuthorizationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID MEMBER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    GroupService service;
    UUID groupId;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE groups (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  PRIMARY KEY (group_id, user_id)
                )
                """);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?), (?,?,?,?), (?,?,?,?)",
                CREATOR, "c@ex.com", "C", "active",
                OTHER, "o@ex.com", "O", "active",
                MEMBER, "m@ex.com", "M", "active");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM group_members");
        jdbc.update("DELETE FROM groups");
        service = new GroupService(jdbc, userSyncService, authorizationService, mock(AuditService.class), mock(eu.socle.identity.IdentityFacade.class));
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        when(userSyncService.syncFromJwt(jwt(CREATOR))).thenReturn(user(CREATOR));
        groupId = service.create(jwt(CREATOR), new CreateGroupRequest("Équipe")).id();
    }

    @Test
    void nonCreator_cannotAddMember_403() {
        when(userSyncService.syncFromJwt(jwt(OTHER))).thenReturn(user(OTHER));
        assertThatThrownBy(() -> service.addMember(
                jwt(OTHER), groupId, new AddMemberRequest(MEMBER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@ex.com");
        u.setDisplayName("U");
        u.setStatus("active");
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
