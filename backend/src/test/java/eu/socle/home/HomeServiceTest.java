// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.home;

import eu.socle.authz.AuthorizationService;
import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentApprovalService;
import eu.socle.home.HomeDtos.HomeResponse;
import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    static DriverManagerDataSource dataSource;
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID OTHER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID SPACE_SECRET = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID DOC_OK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_RESTRICTED = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Max BatchCheck items for home lists: 3 × (5+8+10). */
    static final int MAX_HOME_VIEWER_CHECKS =
            HomeService.CANDIDATE_MULTIPLIER
                    * (HomeService.RESUME_LIMIT
                    + HomeService.RECENT_PUBLISHED_LIMIT
                    + HomeService.TEAM_ACTIVITY_LIMIT);

    @Mock UserSyncService userSyncService;
    @Mock IdentityClaimsMapper claimsMapper;
    @Mock DocumentApprovalService approvalService;
    @Mock ObjectProvider<DocumentApprovalService> approvalProvider;
    @Mock OpenFgaClient openFgaClient;

    AuthorizationService authorizationService;
    HomeService service;
    Jwt jwt;
    AtomicInteger batchCheckItems;
    AtomicInteger batchCheckCalls;
    AtomicInteger listObjectsCalls;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
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
                  folder_id UUID,
                  title TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  visibility TEXT NOT NULL DEFAULT 'space',
                  reliability_score NUMERIC(5,2),
                  updated_by UUID,
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute(new ClassPathResource("db/migration/V31__document_view_counts.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("db/migration/V32__activity_events.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'u@x', 'Tarek Fezai', 'active')", USER);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'o@x', 'Other', 'active')", OTHER);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Identité')", SPACE);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Secret')", SPACE_SECRET);
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, status, visibility, reliability_score, updated_by, updated_at)
                VALUES (?, ?, 'Publié OK', 'valide', 'organisation', 90.0, ?, ?)
                """, DOC_OK, SPACE, USER, Timestamp.from(NOW.minusSeconds(3600)));
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, status, visibility, reliability_score, updated_by, updated_at)
                VALUES (?, ?, 'Secret', 'valide', 'space', 50.0, ?, ?)
                """, DOC_RESTRICTED, SPACE_SECRET, OTHER, Timestamp.from(NOW.minusSeconds(1800)));
    }

    @BeforeEach
    void setUp() throws Exception {
        when(approvalProvider.getIfAvailable()).thenReturn(approvalService);
        when(approvalService.listMine(any())).thenReturn(List.of());

        batchCheckItems = new AtomicInteger();
        batchCheckCalls = new AtomicInteger();
        listObjectsCalls = new AtomicInteger();

        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenAnswer(inv -> {
            listObjectsCalls.incrementAndGet();
            return CompletableFuture.completedFuture(empty);
        });
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            batchCheckCalls.incrementAndGet();
            ClientBatchCheckRequest req = inv.getArgument(0);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                batchCheckItems.addAndGet(1);
                results.add(new ClientBatchCheckSingleResponse(
                        true, item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });

        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga("http://localhost", "s", "m", 1000, false, 50, 4, 500),
                null,
                null,
                null);
        authorizationService = new AuthorizationService(openFgaClient, jdbc, props);

        service = new HomeService(
                jdbc,
                userSyncService,
                authorizationService,
                claimsMapper,
                approvalProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));

        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setDisplayName("Tarek Fezai");
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        when(claimsMapper.givenName(any())).thenReturn("Tarek");
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").claim("given_name", "Tarek").build();

        jdbc.update("DELETE FROM document_view_counts");
        jdbc.update("DELETE FROM activity_events");
        jdbc.update("DELETE FROM documents WHERE id NOT IN (?, ?)", DOC_OK, DOC_RESTRICTED);
    }

    @Test
    void restrictedDocument_doesNotAffectKpisActivityOrRecentlyPublished() {
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
                UUID.randomUUID(), OTHER, DOC_RESTRICTED, SPACE_SECRET, Timestamp.from(NOW.minusSeconds(30)));

        HomeResponse home = service.getHome(jwt);

        assertThat(home.greetingFirstName()).isEqualTo("Tarek");
        // DOC_RESTRICTED: visibility=space hors S_view → absent de la présélection SQL
        assertThat(home.kpis().publishedDocuments()).isEqualTo(1);
        assertThat(home.kpis().viewsThisMonth()).isEqualTo(100);
        assertThat(home.kpis().averageReliabilityPercent()).isEqualTo(90);
        assertThat(home.recentlyPublished()).extracting(r -> r.documentId()).containsExactly(DOC_OK);
        assertThat(home.teamActivity()).extracting(a -> a.documentId()).containsExactly(DOC_OK);
        assertThat(home.teamActivity()).noneMatch(a -> DOC_RESTRICTED.equals(a.documentId()));
    }

    @Test
    void home_with3000PublicDocs_viewerChecksBoundedByDisplayedCandidates() throws Exception {
        // Seed 3000 organisation-visible published docs (+ activity/resume noise)
        for (int i = 0; i < 3000; i++) {
            UUID id = UUID.nameUUIDFromBytes(("pub-" + i).getBytes());
            jdbc.update("""
                    INSERT INTO documents (id, space_id, title, status, visibility, reliability_score, updated_by, updated_at)
                    VALUES (?, ?, ?, 'valide', 'organisation', 80.0, ?, ?)
                    """, id, SPACE, "Pub " + i, USER, Timestamp.from(NOW.minusSeconds(i)));
            if (i < 40) {
                jdbc.update("""
                        INSERT INTO activity_events
                          (id, event_type, actor_user_id, document_id, space_id, payload, created_at)
                        VALUES (?, 'publication', ?, ?, ?, '{}'::jsonb, ?)
                        """,
                        UUID.randomUUID(), OTHER, id, SPACE, Timestamp.from(NOW.minusSeconds(i)));
            }
            if (i < 20) {
                jdbc.update("""
                        INSERT INTO documents (id, space_id, title, status, visibility, updated_by, updated_at)
                        VALUES (?, ?, ?, 'brouillon', 'organisation', ?, ?)
                        """,
                        UUID.nameUUIDFromBytes(("draft-" + i).getBytes()),
                        SPACE, "Draft " + i, USER, Timestamp.from(NOW.minusSeconds(i)));
            }
        }

        batchCheckItems.set(0);
        batchCheckCalls.set(0);
        listObjectsCalls.set(0);

        HomeResponse home = service.getHome(jwt);

        assertThat(home.kpis().publishedDocuments()).isGreaterThanOrEqualTo(3000);
        // ListObjects (readableScope) = 4 appels fixes ; BatchCheck items ≤ sur-sélection listes
        assertThat(listObjectsCalls.get()).isEqualTo(4);
        assertThat(batchCheckItems.get())
                .as("checks viewer bornés par candidats listes, pas par le volume instance")
                .isBetween(1, MAX_HOME_VIEWER_CHECKS);
        assertThat(batchCheckItems.get()).isLessThan(3000);
        // Avant refactor : listViewableDocumentIds(global) → ~3000 checks ; après ≤ 69
        assertThat(batchCheckCalls.get()).isLessThanOrEqualTo(3);

        ArgumentCaptor<ClientBatchCheckRequest> captor =
                ArgumentCaptor.forClass(ClientBatchCheckRequest.class);
        verify(openFgaClient, times(batchCheckCalls.get())).batchCheck(captor.capture());
        int summed = captor.getAllValues().stream().mapToInt(r -> r.getChecks().size()).sum();
        assertThat(summed).isEqualTo(batchCheckItems.get());
    }

    @Test
    void getHome_executesInRealReadOnlyTransaction() {
        var tm = new DataSourceTransactionManager(dataSource);
        var tt = new TransactionTemplate(tm);
        tt.setReadOnly(true);

        assertThatCode(() -> tt.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL transaction_read_only = on");
            service.getHome(jwt);
        })).doesNotThrowAnyException();

        // Pas d'écriture JDBC depuis getHome (purge retirée) — une INSERT échouerait ici.
        assertThatCode(() -> tt.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL transaction_read_only = on");
            jdbc.update("""
                    INSERT INTO document_view_counts (document_id, day, count) VALUES (?, ?, 1)
                    """, DOC_OK, java.sql.Date.valueOf(LocalDate.ofInstant(NOW, ZoneOffset.UTC)));
        })).hasMessageContaining("read-only");
    }

    @Test
    void home_usesReadableScopeListObjects_notGlobalDocumentList() {
        service.getHome(jwt);
        // 4 ListObjects (S_view, S_owner, D_direct, F_view) — pas de ListObjects document#viewer global
        assertThat(listObjectsCalls.get()).isEqualTo(4);
    }
}
