// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.favorites;

import eu.socle.authz.AuthorizationService;
import eu.socle.favorites.FavoriteDtos.FavoriteItem;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FavoriteServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_OK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_DENIED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    FavoriteService service;
    Jwt jwt;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() throws Exception {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE folders (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL, title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        // Schéma V1 puis migration V30 (rename resource_* → target_*).
        jdbc.execute("""
                CREATE TABLE favorites (
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  resource_type TEXT NOT NULL CHECK (resource_type IN ('document','folder','space')),
                  resource_id UUID NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  PRIMARY KEY (user_id, resource_type, resource_id)
                )
                """);
        String favoritesSql = new ClassPathResource("db/migration/V30__favorites.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String stmt : favoritesSql.split(";")) {
            String sql = stmt.lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("--"))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'u@x', 'User', 'active')", USER);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Espace')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc OK')", DOC_OK, SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc Denied')", DOC_DENIED, SPACE);
    }

    @BeforeEach
    void setUp() {
        service = new FavoriteService(
                jdbc, userSyncService, authorizationService, Clock.fixed(NOW, ZoneOffset.UTC));
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setDisplayName("User");
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
        jdbc.update("DELETE FROM favorites");
    }

    @Test
    void put_addsFavorite() {
        when(authorizationService.hasRelation(USER, "document", DOC_OK, "viewer")).thenReturn(true);

        FavoriteItem item = service.put(jwt, FavoriteTargetType.DOCUMENT, DOC_OK);

        assertThat(item.targetType()).isEqualTo("document");
        assertThat(item.targetId()).isEqualTo(DOC_OK);
        assertThat(item.title()).isEqualTo("Doc OK");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ?", Integer.class, USER);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void list_filtersOutInaccessible_withoutDeleting() {
        jdbc.update("""
                INSERT INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (?, 'document', ?, now()), (?, 'document', ?, now() - interval '1 hour')
                """, USER, DOC_OK, USER, DOC_DENIED);

        when(authorizationService.filterByDocumentViewer(eq(USER), anyCollection(), eq("favorites")))
                .thenReturn(List.of(DOC_OK));
        when(authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of());

        List<FavoriteItem> list = service.list(jwt);

        assertThat(list).extracting(FavoriteItem::targetId).containsExactly(DOC_OK);
        Integer stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM favorites WHERE user_id = ?", Integer.class, USER);
        assertThat(stillThere).isEqualTo(2);
    }
}
