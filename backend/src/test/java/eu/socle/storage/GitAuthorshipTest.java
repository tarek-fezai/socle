// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitAuthorshipTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    @Test
    void writeCurrentContent_setsGitAuthorAndCommitterDistinct() throws Exception {
        Path repo = tempDir.resolve("repo");
        GitDocumentStore store = new GitDocumentStore(mock(DocumentVersionRepository.class), repo);

        String head = store.createContent(DOC, DocumentStoreContractTest.tipTap("v1"), ALICE);
        store.writeCurrentContent(
                DOC,
                DocumentStoreContractTest.tipTap("v2"),
                ALICE,
                BOB,
                "Soumission pour approbation",
                head);

        try (Git git = Git.open(repo.toFile())) {
            RevCommit headCommit = git.log().setMaxCount(1).call().iterator().next();
            assertThat(headCommit.getAuthorIdent().getName()).isEqualTo(ALICE.toString());
            assertThat(headCommit.getCommitterIdent().getName()).isEqualTo(BOB.toString());
        }
    }
}
