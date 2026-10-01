// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * GET/Edit doit lire le blob Git, pas la projection Postgres si elle diverge.
 */
class GitReadCanonicalTest {

    static final java.util.UUID DOC = java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final java.util.UUID AUTHOR = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDir;

    @Test
    void readCurrentContent_usesGitHead_notStaleDbProjection() {
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        GitDocumentStore store = new GitDocumentStore(versions, tempDir.resolve("repo"));

        Map<String, Object> canonical = DocumentStoreContractTest.tipTap("ContenuCanoniqueGit");
        store.createContent(DOC, canonical, AUTHOR);

        Map<String, Object> poison = DocumentStoreContractTest.tipTap("ProjectionPostgresObsolete");
        Map<String, Object> read = store.readCurrentContent(DOC, poison);

        assertThat(TipTapMarkdown.toMarkdown(read)).contains("ContenuCanoniqueGit");
        assertThat(TipTapMarkdown.toMarkdown(read)).doesNotContain("ProjectionPostgresObsolete");
    }

    @Test
    void writeCurrentContent_rejectsStaleExpectedHead() {
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        GitDocumentStore store = new GitDocumentStore(versions, tempDir.resolve("repo"));

        String head = store.createContent(DOC, DocumentStoreContractTest.tipTap("A"), AUTHOR);
        store.writeCurrentContent(
                DOC, DocumentStoreContractTest.tipTap("B"), AUTHOR, "b", head);

        assertThatThrownBy(() -> store.writeCurrentContent(
                DOC, DocumentStoreContractTest.tipTap("C"), AUTHOR, "c", head))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
    }
}
