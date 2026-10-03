// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.PersonRef;
import eu.socle.document.DocumentDtos.VersionPage;
import eu.socle.document.DocumentDtos.VersionSummary;
import eu.socle.document.VersionTestSupport.InMemoryVersions;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static eu.socle.document.VersionTestSupport.DOC;
import static eu.socle.document.VersionTestSupport.USER;
import static eu.socle.document.VersionTestSupport.docOf;
import static eu.socle.document.VersionTestSupport.jwt;
import static eu.socle.document.VersionTestSupport.p;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Liste des versions : auteur résolu + statistiques de lignes calculées à la lecture. */
class DocumentVersionListStatsTest {

    static final UUID ALICE = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");

    @TempDir Path tempDir;

    DocumentStore store;
    InMemoryVersions versions;
    DocumentRepository documents;
    JdbcTemplate jdbc;
    DocumentService service;

    @AfterEach
    void closeStore() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
    }

    private static Map<String, Object> bodyOfParagraphs(int count, int modifiedFrom) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            blocks.add(p(i >= modifiedFrom ? "modifié " + i : "paragraphe " + i));
        }
        return docOf(blocks);
    }

    @SuppressWarnings("unchecked")
    private void setUp(String provider, List<Map<String, Object>> bodies) throws Exception {
        versions = new InMemoryVersions();
        store = VersionTestSupport.store(provider, versions, tempDir);
        DocumentEntity entity = VersionTestSupport.replay(store, bodies, ALICE);
        // Marque la v1 comme sans auteur (données migrées).
        versions.rows.stream().filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow().setAuthorId(null);
        documents = mock(DocumentRepository.class);
        when(documents.findActiveById(DOC)).thenReturn(Optional.of(entity));
        UserSyncService userSync = mock(UserSyncService.class);
        when(userSync.syncFromJwt(any())).thenReturn(VersionTestSupport.user(USER, "Viewer"));
        AuthorizationService authz = mock(AuthorizationService.class);

        jdbc = mock(JdbcTemplate.class);
        // Résolution groupée des auteurs : SELECT ... FROM users WHERE id IN (...)
        when(jdbc.query(contains("FROM users WHERE id IN"), any(ResultSetExtractor.class),
                any(Object[].class))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getObject("id")).thenReturn(ALICE);
            when(rs.getString("display_name")).thenReturn("Alice Martin");
            when(rs.getString("avatar_initials")).thenReturn(null);
            when(rs.getString("status")).thenReturn("active");
            return ((ResultSetExtractor<Object>) inv.getArgument(1)).extractData(rs);
        });
        service = VersionTestSupport.service(store, documents, userSync, authz, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void firstVersion_isAllAdded_laterVersionsAreLineDiffs(String provider) throws Exception {
        // v1: 5 paragraphes ; v2: 5 dont 3 modifiés (3-5) ; v3 (courante) : idem v2
        setUp(provider, List.of(
                bodyOfParagraphs(5, 99),
                bodyOfParagraphs(5, 3),
                bodyOfParagraphs(5, 3)));

        VersionPage page = service.listVersions(jwt(), DOC, 0, 20);

        assertThat(page.total()).isEqualTo(2); // archivées : v1, v2
        VersionSummary v2 = page.items().get(0);
        VersionSummary v1 = page.items().get(1);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v1.versionNo()).isEqualTo(1);

        assertThat(v1.linesAdded()).isEqualTo(5);   // 5 lignes non vides, rien de supprimé
        assertThat(v1.linesRemoved()).isZero();
        assertThat(v2.linesAdded()).isEqualTo(3);
        assertThat(v2.linesRemoved()).isEqualTo(3);
        assertThat(v1.current()).isFalse();
        assertThat(v2.current()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void authors_areResolvedInBatch_andNullAuthorIsSystem(String provider) throws Exception {
        setUp(provider, List.of(bodyOfParagraphs(2, 99), bodyOfParagraphs(2, 1), bodyOfParagraphs(2, 1)));

        VersionPage page = service.listVersions(jwt(), DOC, 0, 20);
        VersionSummary v2 = page.items().get(0);
        VersionSummary v1 = page.items().get(1);

        assertThat(v2.authorId()).isEqualTo(ALICE);
        assertThat(v2.authorDisplayName()).isEqualTo("Alice Martin");
        assertThat(v2.authorInitials()).isEqualTo("AM");
        assertThat(v1.authorId()).isNull();
        assertThat(v1.authorDisplayName()).isEqualTo("Système (migration)");
        assertThat(v1.authorInitials()).isEqualTo(VersionSummary.SYSTEM_AUTHOR_INITIALS);
        assertThat(PersonRef.initialsOf("Alice Martin")).isEqualTo("AM");

        // Un seul SELECT users pour toute la page.
        verify(jdbc, org.mockito.Mockito.times(1)).query(
                contains("FROM users WHERE id IN"), any(ResultSetExtractor.class), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void pageOf20_isComputedWithoutAnyWrite(String provider) throws Exception {
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (int v = 1; v <= 26; v++) {
            bodies.add(bodyOfParagraphs(10, 11 - Math.min(v, 10))); // un paragraphe de plus modifié à chaque version
        }
        setUp(provider, bodies);
        int savesBefore = versions.rows.size();

        VersionPage page = service.listVersions(jwt(), DOC, 0, 20);

        assertThat(page.items()).hasSize(20);
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.items().get(0).versionNo()).isEqualTo(25);
        assertThat(page.items().get(19).versionNo()).isEqualTo(6);
        // v2→v10 modifient 1 paragraphe de plus à chaque fois ; v>=10 identiques à leur précédente
        VersionSummary v6 = page.items().get(19);
        assertThat(v6.linesAdded()).isEqualTo(1);
        assertThat(v6.linesRemoved()).isEqualTo(1);
        VersionSummary v25 = page.items().get(0);
        assertThat(v25.linesAdded()).isZero();
        assertThat(v25.linesRemoved()).isZero();

        // Aucune écriture : ni version, ni document, ni SQL de mise à jour.
        assertThat(versions.rows).hasSize(savesBefore);
        verify(versions.repository, never()).delete(any());
        verify(documents, never()).save(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void overLineCeiling_fallsBackToSizeDelta_withoutDiffing(String provider) throws Exception {
        setUp(provider, List.of(bodyOfParagraphs(5, 99), bodyOfParagraphs(8, 99), bodyOfParagraphs(8, 99)));
        DiffProperties props = new DiffProperties();
        props.setMaxLines(4);
        service.setDiffProperties(props);

        VersionPage page = service.listVersions(jwt(), DOC, 0, 20);

        VersionSummary v2 = page.items().get(0);
        assertThat(v2.linesAdded()).isEqualTo(3);   // 8 - 5 paragraphes non vides
        assertThat(v2.linesRemoved()).isZero();
    }

    @Test
    void jsonShape_isBackwardCompatible() throws Exception {
        VersionSummary legacy = new VersionSummary(3, USER, USER, "résumé", java.time.Instant.parse("2026-10-01T10:00:00Z"));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var json = mapper.valueToTree(legacy);
        for (String field : List.of("versionNo", "authorId", "archivedBy", "changeSummary", "createdAt",
                "authorDisplayName", "authorInitials", "linesAdded", "linesRemoved", "current")) {
            assertThat(json.has(field)).as(field).isTrue();
        }
    }
}
