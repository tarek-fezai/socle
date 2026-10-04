// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRelatedLinksService.RelatedLinks;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentRelatedLinksServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OUT_OK = UUID.fromString("22222222-2222-2222-2222-2222222222a1");
    static final UUID OUT_DENIED = UUID.fromString("22222222-2222-2222-2222-2222222222a2");
    static final UUID OUT_DELETED = UUID.fromString("22222222-2222-2222-2222-2222222222a3");
    static final UUID IN_OK = UUID.fromString("22222222-2222-2222-2222-2222222222b1");
    static final UUID IN_DENIED = UUID.fromString("22222222-2222-2222-2222-2222222222b2");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    DocumentRelatedLinksService service;
    Jwt jwt;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_links (
                  source_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  target_id UUID NOT NULL,
                  source_space_id UUID NOT NULL,
                  link_type TEXT NOT NULL DEFAULT 'transclusion',
                  PRIMARY KEY (source_id, target_id, link_type)
                )
                """);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        insertDoc(DOC, "Courant", false);
        insertDoc(OUT_OK, "Cible visible", false);
        insertDoc(OUT_DENIED, "Cible secrète", false);
        insertDoc(OUT_DELETED, "Cible supprimée", true);
        insertDoc(IN_OK, "Source visible", false);
        insertDoc(IN_DENIED, "Source secrète", false);
        link(DOC, OUT_OK);
        link(DOC, OUT_DENIED);
        link(DOC, OUT_DELETED);
        link(IN_OK, DOC);
        link(IN_DENIED, DOC);
    }

    static void insertDoc(UUID id, String title, boolean deleted) {
        jdbc.update("INSERT INTO documents (id, space_id, title, deleted_at) VALUES (?, ?, ?, "
                        + (deleted ? "now()" : "NULL") + ")",
                id, SPACE, title);
    }

    static void link(UUID source, UUID target) {
        jdbc.update("INSERT INTO document_links (source_id, target_id, source_space_id) VALUES (?, ?, ?)",
                source, target, SPACE);
    }

    @BeforeEach
    void setUp() {
        service = new DocumentRelatedLinksService(jdbc, userSyncService, authorizationService);
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();

        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.filterByDocumentViewer(eq(USER), anyCollection(), eq("document-links")))
                .thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(1)).stream()
                        .filter(id -> Set.of(OUT_OK, IN_OK).contains(id))
                        .toList());
    }

    @Test
    void returnsOnlyViewableLinks_withTitles() {
        RelatedLinks links = service.links(jwt, DOC);

        assertThat(links.outgoing()).extracting(DocumentRelatedLinksService.LinkedDocument::id)
                .containsExactly(OUT_OK);
        assertThat(links.outgoing().getFirst().title()).isEqualTo("Cible visible");
        assertThat(links.incoming()).extracting(DocumentRelatedLinksService.LinkedDocument::id)
                .containsExactly(IN_OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void checksOnlyCandidates_notDeletedDocuments_inOneBatch() {
        service.links(jwt, DOC);

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(authorizationService).filterByDocumentViewer(eq(USER), captor.capture(), eq("document-links"));
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(OUT_OK, OUT_DENIED, IN_OK, IN_DENIED)
                .doesNotContain(OUT_DELETED, DOC);
        assertThat(captor.getValue().size())
                .isLessThanOrEqualTo(2 * DocumentRelatedLinksService.MAX_CANDIDATES_PER_DIRECTION);
    }

    @Test
    void nonViewerOfCurrentDocument_getsNotFound_withoutBatchCheck() {
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(false);

        assertThatThrownBy(() -> service.links(jwt, DOC))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(authorizationService, never()).filterByDocumentViewer(any(), anyCollection(), any());
    }

    @Test
    void documentWithoutLinks_returnsEmptyLists() {
        UUID lonely = UUID.fromString("99999999-9999-9999-9999-999999999999");
        insertDoc(lonely, "Seul", false);
        when(authorizationService.hasRelation(USER, "document", lonely, "viewer")).thenReturn(true);
        when(authorizationService.filterByDocumentViewer(eq(USER), anyCollection(), any()))
                .thenReturn(List.of());

        RelatedLinks links = service.links(jwt, lonely);

        assertThat(links.outgoing()).isEmpty();
        assertThat(links.incoming()).isEmpty();
    }

    @Test
    void impactedIncoming_visiblePlusHiddenCount_excludesOutgoing() {
        DocumentRelatedLinksService.ImpactedIncoming impacted = service.impactedIncoming(jwt, DOC);

        assertThat(impacted.visible()).extracting(DocumentRelatedLinksService.LinkedDocument::id)
                .containsExactly(IN_OK);
        assertThat(impacted.visible().getFirst().title()).isEqualTo("Source visible");
        assertThat(impacted.hiddenCount()).isEqualTo(1);
        assertThat(impacted.visible()).extracting(DocumentRelatedLinksService.LinkedDocument::id)
                .doesNotContain(OUT_OK, OUT_DENIED, IN_DENIED);
    }
}
