// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentDtos.VersionPage;
import eu.socle.document.DocumentDtos.VersionSummary;
import eu.socle.document.VersionTestSupport.InMemoryVersions;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.HashMap;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Même séquence create → 3 updates (résumés distincts) → 1 restore :
 * {@code listVersions} renvoie les mêmes (versionNo, résumé, auteur de contenu)
 * en relational et en git.
 */
class DocumentVersionChangeSummaryParityTest {

    @TempDir Path tempDir;

    DocumentStore store;
    InMemoryVersions versions;
    DocumentEntity entity;
    DocumentService service;

    @AfterEach
    void closeStore() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
    }

    private void setUp(String provider) {
        versions = new InMemoryVersions();
        store = VersionTestSupport.store(provider, versions, tempDir);
        String head = store.createContent(DOC, body("v1"), USER);

        entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(VersionTestSupport.SPACE);
        entity.setTitle("Doc");
        entity.setBody(body("v1"));
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(1);
        entity.setCurrentChangeSummary(null);
        entity.setGitHeadSha(head);
        entity.setCreatedBy(USER);
        entity.setUpdatedBy(USER);

        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(documents.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(entity));
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSyncService userSync = mock(UserSyncService.class);
        when(userSync.syncFromJwt(any())).thenReturn(VersionTestSupport.user(USER, "Author"));
        AuthorizationService authz = mock(AuthorizationService.class);
        service = VersionTestSupport.service(store, documents, userSync, authz, mock(JdbcTemplate.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relational", "git"})
    void create_threeUpdates_restore_sameSummariesAndAuthors(String provider) {
        setUp(provider);

        update("S2", "v2");
        update("S3", "v3");
        update("S4", "v4");
        // current=4 ; restore v2 → archive 4 with S4, current=5 with restore summary
        service.restore(jwt(), DOC, 2, entity.getCurrentVersionNo());

        VersionPage page = service.listVersions(jwt(), DOC, 0, 50);
        List<Triple> triples = page.items().stream()
                .map(v -> new Triple(v.versionNo(), v.changeSummary(), v.authorId()))
                .toList();

        assertThat(triples).containsExactly(
                new Triple(5, "Restauration de la version 2", USER),
                new Triple(4, "S4", USER),
                new Triple(3, "S3", USER),
                new Triple(2, "S2", USER),
                new Triple(1, null, USER));
        assertThat(page.items().getFirst().current()).isTrue();
        assertThat(page.total()).isEqualTo(5);
    }

    private void update(String summary, String text) {
        service.update(jwt(), DOC, new UpdateDocumentRequest("Doc", body(text), summary));
    }

    private static Map<String, Object> body(String text) {
        return new HashMap<>(docOf(List.of(p(text))));
    }

    private record Triple(int versionNo, String changeSummary, UUID authorId) {}
}
