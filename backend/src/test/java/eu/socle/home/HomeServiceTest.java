// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.home;

import eu.socle.activity.ActivityEventService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentApprovalService;
import eu.socle.home.HomeDtos.HomeResponse;
import eu.socle.identity.IdentityClaimsMapper;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID OTHER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC_OK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_RESTRICTED = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock IdentityClaimsMapper claimsMapper;
    @Mock DocumentApprovalService approvalService;
    @Mock ObjectProvider<DocumentApprovalService> approvalProvider;
    @Mock ActivityEventService activityEventService;

    HomeService service;
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
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  reliability_score NUMERIC(5,2),
                  updated_by UUID,
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute(new ClassPathResource("db/migration/V30__document_view_counts.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("db/migration/V31__activity_events.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'u@x', 'Tarek Fezai', 'active')", USER);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'o@x', 'Other', 'active')", OTHER);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Identité')", SPACE);
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, status, reliability_score, updated_by, updated_at)
                VALUES (?, ?, 'Publié OK', 'valide', 90.0, ?, ?)
                """, DOC_OK, SPACE, USER, Timestamp.from(NOW.minusSeconds(3600)));
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, status, reliability_score, updated_by, updated_at)
                VALUES (?, ?, 'Secret', 'valide', 50.0, ?, ?)
                """, DOC_RESTRICTED, SPACE, OTHER, Timestamp.from(NOW.minusSeconds(1800)));
    }

    @BeforeEach
    void setUp() {
        when(approvalProvider.getIfAvailable()).thenReturn(approvalService);
        when(approvalService.listMine(any())).thenReturn(List.of());
        when(activityEventService.purgeOlderThanRetention()).thenReturn(0);

        service = new HomeService(
                jdbc,
                userSyncService,
                authorizationService,
                claimsMapper,
                approvalProvider,
                activityEventService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setDisplayName("Tarek Fezai");
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        when(claimsMapper.givenName(any())).thenReturn("Tarek");
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").claim("given_name", "Tarek").build();

        jdbc.update("DELETE FROM document_view_counts");
        jdbc.update("DELETE FROM activity_events");
    }

    @Test
    void restrictedDocument_doesNotAffectKpisActivityOrRecentlyPublished() {
        when(authorizationService.listViewableDocumentIds(eq(USER), any(DocumentScope.class)))
                .thenReturn(List.of(DOC_OK));

        LocalDate day = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO document_view_counts (document_id, day, count) VALUES (?, ?, 100), (?, ?, 9999)
                """, DOC_OK, java.sql.Date.valueOf(day), DOC_RESTRICTED, java.sql.Date.valueOf(day));

        jdbc.update("""
                INSERT INTO activity_events
                  (id, event_type, actor_user_id, document_id, space_id, payload, created_at)
                VALUES (?, 'publication', ?, ?, ?, '{}'::jsonb, ?),
                       (?, 'comment', ?, ?, ?, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(), OTHER, DOC_OK, SPACE, Timestamp.from(NOW.minusSeconds(60)),
                UUID.randomUUID(), OTHER, DOC_RESTRICTED, SPACE, Timestamp.from(NOW.minusSeconds(30)));

        HomeResponse home = service.getHome(jwt);

        assertThat(home.greetingFirstName()).isEqualTo("Tarek");
        assertThat(home.kpis().publishedDocuments()).isEqualTo(1);
        assertThat(home.kpis().viewsThisMonth()).isEqualTo(100);
        assertThat(home.kpis().averageReliabilityPercent()).isEqualTo(90);
        assertThat(home.recentlyPublished()).extracting(r -> r.documentId()).containsExactly(DOC_OK);
        assertThat(home.teamActivity()).extracting(a -> a.documentId()).containsExactly(DOC_OK);
        assertThat(home.teamActivity()).noneMatch(a -> DOC_RESTRICTED.equals(a.documentId()));
    }

    @Test
    void home_usesSingleBoundedOpenFgaList() {
        when(authorizationService.listViewableDocumentIds(eq(USER), any(DocumentScope.class)))
                .thenReturn(List.of(DOC_OK));

        service.getHome(jwt);

        verify(authorizationService, times(1))
                .listViewableDocumentIds(eq(USER), any(DocumentScope.class));
        verify(authorizationService, never()).hasRelation(any(), any(), any(), any());
        verify(authorizationService, never()).filterByDocumentViewer(any(), any());
    }
}
