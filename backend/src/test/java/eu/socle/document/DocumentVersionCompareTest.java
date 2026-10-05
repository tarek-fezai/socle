// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.CompareHunk;
import eu.socle.document.DocumentDtos.CompareLine;
import eu.socle.document.DocumentDtos.VersionCompareResponse;
import eu.socle.document.DocumentDtos.VersionDiffResponse;
import eu.socle.document.VersionTestSupport.InMemoryVersions;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static eu.socle.document.VersionTestSupport.DOC;
import static eu.socle.document.VersionTestSupport.USER;
import static eu.socle.document.VersionTestSupport.docOf;
import static eu.socle.document.VersionTestSupport.h;
import static eu.socle.document.VersionTestSupport.jwt;
import static eu.socle.document.VersionTestSupport.p;
import static eu.socle.document.VersionTestSupport.transclusion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Compare Markdown ligne à ligne — stores relational ET git ; BodyDiff inchangé. */
class DocumentVersionCompareTest {

    @TempDir Path tempDir;

    DocumentStore store;
    InMemoryVersions versions;
    DocumentRepository documents;
    UserSyncService userSync;
    AuthorizationService authz;
    DocumentService service;

    @AfterEach
    void closeStore() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
    }

    private void setUp(String provider, List<Map<String, Object>> bodies) {
        versions = new InMemoryVersions();
        store = VersionTestSupport.store(provider, versions, tempDir);
        DocumentEntity entity = VersionTestSupport.replay(store, bodies, USER);
        documents = mock(DocumentRepository.class);
        when(documents.findActiveById(DOC)).thenReturn(Optional.of(entity));
        userSync = mock(UserSyncService.class);
        when(userSync.syncFromJwt(any())).thenReturn(VersionTestSupport.user(USER, "Alice"));
        authz = mock(AuthorizationService.class);
        service = VersionTestSupport.service(store, documents, userSync, authz, null);
    }

    /** # Titre / ## Section / 30 paragraphes — la variante change le paragraphe {@code changed}. */
    private static Map<String, Object> article(int changedParagraph, String changedText) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(h(1, "Titre"));
        blocks.add(p("Introduction"));
        blocks.add(h(2, "Section B"));
        for (int i = 1; i <= 30; i++) {
            blocks.add(p(i == changedParagraph ? changedText : "paragraphe " + i));
        }
        return docOf(blocks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_collapsesUnchangedRegions_andHighlightsWords(String provider) {
        setUp(provider, List.of(article(0, ""), article(15, "paragraphe 999"), article(15, "paragraphe 999")));

        VersionCompareResponse r = service.compare(jwt(), DOC, 1, 2, "lines");

        assertThat(r.documentId()).isEqualTo(DOC);
        assertThat(r.fromVersion()).isEqualTo(1);
        assertThat(r.toVersion()).isEqualTo(2);
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);

        // [replié] [hunk modifié] [replié]
        assertThat(r.hunks()).hasSize(3);
        CompareHunk head = r.hunks().get(0);
        CompareHunk changed = r.hunks().get(1);
        CompareHunk tail = r.hunks().get(2);
        assertThat(head.lines()).isEmpty();
        assertThat(head.collapsedUnchanged()).isGreaterThan(0);
        assertThat(tail.lines()).isEmpty();
        assertThat(tail.collapsedUnchanged()).isGreaterThan(0);
        assertThat(changed.collapsedUnchanged()).isZero();
        assertThat(changed.header()).isEqualTo("Section B");

        List<CompareLine> lines = changed.lines();
        assertThat(lines.stream().filter(l -> "context".equals(l.kind())).count()).isEqualTo(6);
        assertThat(lines).extracting(CompareLine::kind)
                .startsWith("context", "context", "context")
                .endsWith("context", "context", "context");
        CompareLine del = lines.stream().filter(l -> "del".equals(l.kind())).findFirst().orElseThrow();
        CompareLine add = lines.stream().filter(l -> "add".equals(l.kind())).findFirst().orElseThrow();
        assertThat(del.text()).isEqualTo("paragraphe 15");
        assertThat(add.text()).isEqualTo("paragraphe 999");
        assertThat(del.spans()).anyMatch(s -> "del".equals(s.kind()) && "15".equals(s.text()));
        assertThat(add.spans()).anyMatch(s -> "add".equals(s.kind()) && "999".equals(s.text()));

        // Lignes totales = replié + déplié (aucune ligne perdue côté « avant »).
        int before = head.collapsedUnchanged() + tail.collapsedUnchanged()
                + (int) lines.stream().filter(l -> l.oldNo() != null).count();
        assertThat(before).isEqualTo(MarkdownLineDiff.linesOf(article(0, "")).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_withCurrentVersion_usesCurrentContent(String provider) {
        setUp(provider, List.of(article(0, ""), article(15, "paragraphe 999"), article(20, "vingt")));

        VersionCompareResponse r = service.compare(jwt(), DOC, 2, 3, "lines");

        assertThat(r.added()).isEqualTo(2);   // 15 restauré à l'original + 20 modifié
        assertThat(r.removed()).isEqualTo(2);
        assertThat(r.hunks().stream().flatMap(hk -> hk.lines().stream()))
                .anyMatch(l -> "add".equals(l.kind()) && "vingt".equals(l.text()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_identicalVersions_isSingleCollapsedHunk(String provider) {
        setUp(provider, List.of(article(0, ""), article(0, ""), article(0, "")));

        VersionCompareResponse r = service.compare(jwt(), DOC, 1, 2, null);

        assertThat(r.added()).isZero();
        assertThat(r.removed()).isZero();
        assertThat(r.hunks()).hasSize(1);
        assertThat(r.hunks().getFirst().lines()).isEmpty();
        assertThat(r.hunks().getFirst().collapsedUnchanged()).isGreaterThan(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_transclusionStaysAsDirective_neverResolved(String provider) {
        UUID target1 = UUID.fromString("12121212-1212-1212-1212-121212121212");
        UUID target2 = UUID.fromString("34343434-3434-3434-3434-343434343434");
        setUp(provider, List.of(
                docOf(List.of(p("avant"), transclusion(target1))),
                docOf(List.of(p("avant"), transclusion(target2))),
                docOf(List.of(p("avant"), transclusion(target2)))));

        VersionCompareResponse r = service.compare(jwt(), DOC, 1, 2, "lines");

        List<CompareLine> all = r.hunks().stream().flatMap(hk -> hk.lines().stream()).toList();
        assertThat(all).anyMatch(l -> "del".equals(l.kind())
                && l.text().equals("::transclusion{documentId=\"" + target1 + "\"}"));
        assertThat(all).anyMatch(l -> "add".equals(l.kind())
                && l.text().equals("::transclusion{documentId=\"" + target2 + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_missingVersion_is404(String provider) {
        setUp(provider, List.of(article(0, ""), article(1, "x"), article(2, "y")));

        assertThatThrownBy(() -> service.compare(jwt(), DOC, 1, 42, "lines"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_overLineCeiling_is413(String provider) {
        setUp(provider, List.of(article(0, ""), article(1, "x"), article(2, "y")));
        DiffProperties props = new DiffProperties();
        props.setMaxLines(20);
        service.setDiffProperties(props);

        assertThatThrownBy(() -> service.compare(jwt(), DOC, 1, 2, "lines"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));

        // Plafond relevé : la comparaison passe.
        props.setMaxLines(20000);
        assertThat(service.compare(jwt(), DOC, 1, 2, "lines").added()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_unknownMode_is400(String provider) {
        setUp(provider, List.of(article(0, ""), article(1, "x")));

        assertThatThrownBy(() -> service.compare(jwt(), DOC, 1, 2, "words"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void compare_requiresViewer(String provider) {
        setUp(provider, List.of(article(0, ""), article(1, "x")));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé"))
                .when(authz).requireDocumentRelation(USER, DOC, "viewer");

        assertThatThrownBy(() -> service.compare(jwt(), DOC, 1, 2, "lines"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** Non-régression : l'endpoint BodyDiff (ApprovalsPage) garde sa forme chemin/op/avant/après. */
    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void bodyDiff_endpointUnchanged(String provider) {
        setUp(provider, List.of(article(0, ""), article(15, "paragraphe 999"), article(15, "paragraphe 999")));

        VersionDiffResponse diff = service.diff(jwt(), DOC, 1, 2);

        assertThat(diff.documentId()).isEqualTo(DOC);
        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(2);
        assertThat(diff.changes()).isNotEmpty();
        assertThat(diff.changes()).noneMatch(c -> c.path() == null || c.op() == null);
        if ("relational".equals(provider)) {
            // BodyDiff JSON : chemins de nœuds TipTap
            assertThat(diff.changes()).anyMatch(c -> "modified".equals(c.op()));
        } else {
            // Git : un changement « modified » sur le fichier Markdown, avant/après = Markdown complet
            assertThat(diff.changes()).hasSize(1);
            assertThat(diff.changes().getFirst().op()).isEqualTo("modified");
            assertThat(String.valueOf(diff.changes().getFirst().after())).contains("paragraphe 999");
        }
    }
}
