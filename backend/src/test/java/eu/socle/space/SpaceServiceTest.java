// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.space;

import eu.socle.authz.AuthorizationService;
import eu.socle.space.SpaceDtos.AddOwnerRequest;
import eu.socle.space.SpaceDtos.CreateSpaceRequest;
import eu.socle.space.SpaceDtos.GovernanceView;
import eu.socle.space.SpaceDtos.SpaceView;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpaceServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SEED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    SpaceService service;

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
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, color TEXT,
                  external_reference TEXT NOT NULL DEFAULT 'open',
                  default_visibility TEXT NOT NULL DEFAULT 'organisation',
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE space_owners (
                  space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  is_responsible BOOLEAN NOT NULL DEFAULT false,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  PRIMARY KEY (space_id, user_id)
                )
                """);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?), (?,?,?,?)",
                USER_A, "a@example.com", "A", "active",
                USER_B, "b@example.com", "B", "active");
        jdbc.update("INSERT INTO spaces (id, name, color) VALUES (?,?,?)",
                SEED, "Espace par défaut", "#2F6FED");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM space_owners");
        jdbc.update("DELETE FROM spaces WHERE id <> ?", SEED);
        service = new SpaceService(jdbc, userSyncService, authorizationService, mock(eu.socle.audit.AuditService.class));
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        doNothing().when(authorizationService).revokePermission(any(), any(), any(), any(), any());
        when(authorizationService.listViewableSpaceIds(any())).thenReturn(List.of());
        when(authorizationService.hasRelation(any(), eq("space"), any(), any())).thenReturn(false);
    }

    @Test
    void create_setsCreatorAsResponsibleAndOwner() {
        when(authorizationService.hasRelation(eq(USER_A), eq("space"), any(), eq("owner"))).thenReturn(true);
        when(authorizationService.hasRelation(eq(USER_A), eq("space"), any(), eq("viewer"))).thenReturn(true);

        SpaceView created = service.create(jwt(USER_A), new CreateSpaceRequest("Identité", "#111"));
        assertThat(created.isResponsible()).isTrue();
        assertThat(created.isOwner()).isTrue();
        assertThat(created.externalReference()).isEqualTo("open");
        assertThat(created.defaultVisibility()).isEqualTo("organisation");

        String mode = jdbc.queryForObject(
                "SELECT external_reference FROM spaces WHERE id = ?", String.class, created.id());
        assertThat(mode).isEqualTo("open");
        String dv = jdbc.queryForObject(
                "SELECT default_visibility FROM spaces WHERE id = ?", String.class, created.id());
        assertThat(dv).isEqualTo("organisation");

        Integer owners = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ? AND user_id = ? AND is_responsible",
                Integer.class, created.id(), USER_A);
        assertThat(owners).isEqualTo(1);

        verify(authorizationService).grantPermission(
                eq("space"), eq(created.id()), eq("owner"), eq("user"), eq(USER_A));
    }

    @Test
    void twoSpaces_haveIsolatedOwners() {
        SpaceView spaceA = service.create(jwt(USER_A), new CreateSpaceRequest("A", null));
        SpaceView spaceB = service.create(jwt(USER_B), new CreateSpaceRequest("B", null));

        // FGA owner check must be space-specific (pas any()), sinon cross-space faux positifs
        when(authorizationService.hasRelation(eq(USER_A), eq("space"), eq(spaceA.id()), eq("owner")))
                .thenReturn(true);
        when(authorizationService.hasRelation(eq(USER_B), eq("space"), eq(spaceB.id()), eq("owner")))
                .thenReturn(true);
        when(authorizationService.hasRelation(eq(USER_A), eq("space"), eq(spaceB.id()), eq("owner")))
                .thenReturn(false);
        when(authorizationService.hasRelation(eq(USER_B), eq("space"), eq(spaceA.id()), eq("owner")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.addOwner(
                jwt(USER_B), spaceA.id(), new AddOwnerRequest(USER_B, false)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.governance(jwt(USER_A), spaceB.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void nonOwner_cannotUpdateSpace() {
        when(authorizationService.hasRelation(eq(USER_A), eq("space"), any(), eq("owner"))).thenReturn(true);
        SpaceView space = service.create(jwt(USER_A), new CreateSpaceRequest("Privé", null));

        assertThatThrownBy(() -> service.update(
                jwt(USER_B), space.id(), new SpaceDtos.UpdateSpaceRequest("Hack", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).grantPermission(
                eq("space"), eq(space.id()), eq("owner"), eq("user"), eq(USER_B));
    }

    @Test
    void seedSpace_stillResolvable_withoutHardcodedDefaultInService() {
        when(authorizationService.hasRelation(USER_A, "space", SEED, "owner")).thenReturn(true);
        when(authorizationService.hasRelation(USER_A, "space", SEED, "viewer")).thenReturn(true);

        SpaceView view = service.get(jwt(USER_A), SEED);
        assertThat(view.id()).isEqualTo(SEED);
        assertThat(view.name()).isEqualTo("Espace par défaut");

        GovernanceView gov = service.governance(jwt(USER_A), SEED);
        assertThat(gov.owners()).anyMatch(o -> o.userId().equals(USER_A) && o.responsible());
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)
                )
                .build();
    }
}
