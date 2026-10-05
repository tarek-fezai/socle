// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.document.DocumentVersionRepository;
import eu.socle.folder.FolderDtos.MoveDocumentRequest;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static eu.socle.folder.FolderTestSupport.SPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Le chemin Git d'un document est {@code documents/{id}.md} : il ne dépend jamais du
 * dossier. Déplacer un document (changement de {@code folder_id}) ne touche donc ni au
 * dépôt Git ni à l'historique.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderMoveGitPathTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TARGET = UUID.fromString("f0000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void gitPath_isDocumentsIdMd_regardlessOfFolder() throws Exception {
        Path repo = tempDir.resolve("repo");
        try (GitDocumentStore store = new GitDocumentStore(mock(DocumentVersionRepository.class), repo)) {
            store.createContent(DOC, tipTap("Contenu"), AUTHOR);

            assertThat(repo.resolve("documents").resolve(DOC + ".md")).isRegularFile();
            assertThat(markdownFiles(repo)).containsExactly("documents/" + DOC + ".md");
        }
    }

    @Test
    void moveDocument_doesNotChangeGitPathNorHistory() throws Exception {
        Path repo = tempDir.resolve("repo");
        try (GitDocumentStore store = new GitDocumentStore(mock(DocumentVersionRepository.class), repo)) {
            String headBefore = store.createContent(DOC, tipTap("Contenu"), AUTHOR);
            String contentBefore = Files.readString(repo.resolve("documents").resolve(DOC + ".md"));
            List<String> filesBefore = markdownFiles(repo);

            FolderTestSupport fx = new FolderTestSupport();
            doNothing().when(fx.authorizationService).requireDocumentRelation(any(), any(), any());
            doNothing().when(fx.authorizationService).requireFolderRelation(any(), any(), any());
            fx.folder(TARGET, SPACE, null, "Procédures").doc(DOC, SPACE, null, "Doc");

            Map<String, Object> out = fx.service(5)
                    .moveDocument(FolderTestSupport.jwt(), DOC, new MoveDocumentRequest(TARGET, null));

            assertThat(out).containsEntry("folderId", TARGET);
            // même chemin, même contenu, aucun nouveau commit
            assertThat(markdownFiles(repo)).isEqualTo(filesBefore);
            assertThat(Files.readString(repo.resolve("documents").resolve(DOC + ".md")))
                    .isEqualTo(contentBefore);
            assertThat(headSha(repo)).isEqualTo(headBefore);
            assertThat(markdownFiles(repo)).containsExactly("documents/" + DOC + ".md");
        }
    }

    private static String headSha(Path repo) throws Exception {
        try (Repository r = new FileRepositoryBuilder()
                .setGitDir(repo.resolve(".git").toFile()).build()) {
            return r.resolve("HEAD").getName();
        }
    }

    @Test
    void folderService_hasNoDocumentStoreDependency() {
        // FolderService ne peut pas écrire dans Git : aucun DocumentStore dans ses champs.
        boolean touchesStore = Arrays.stream(FolderService.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(DocumentStore.class::isAssignableFrom);
        assertThat(touchesStore).isFalse();
    }

    private static List<String> markdownFiles(Path repo) throws Exception {
        try (Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(repo.resolve(".git")))
                    .map(p -> repo.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, Object> tipTap(String text) {
        Map<String, Object> textNode = new HashMap<>();
        textNode.put("type", "text");
        textNode.put("text", text);
        Map<String, Object> para = new HashMap<>();
        para.put("type", "paragraph");
        para.put("content", List.of(textNode));
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(para));
        return doc;
    }
}
