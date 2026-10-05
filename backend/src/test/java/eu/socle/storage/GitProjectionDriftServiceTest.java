// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.TransclusionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitProjectionDriftServiceTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TARGET = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir Path tempDir;
    @Mock DocumentRepository documentRepository;

    @Test
    void relationalProvider_returnsEmptyWithHint() {
        StorageProperties props = new StorageProperties();
        props.setProvider("relational");
        props.validate();
        GitProjectionDriftService svc = new GitProjectionDriftService(
                documentRepository, mock(DocumentStore.class), props);

        var report = svc.listContentDrift();
        assertThat(report.storageProvider()).isEqualTo("relational");
        assertThat(report.items()).isEmpty();
    }

    @Test
    void gitProvider_listsDocWhenProjectionDiffersFromBlob() {
        StorageProperties props = new StorageProperties();
        props.setProvider("git");
        props.setGitRepositoryPath(tempDir.resolve("repo").toString());
        props.validate();

        GitDocumentStore store = new GitDocumentStore(
                mock(eu.socle.document.DocumentVersionRepository.class),
                tempDir.resolve("repo"));
        store.createContent(DOC, tipTap("Sans tx"), AUTHOR);

        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setTitle("Composite abîmé");
        entity.setBody(composite(TARGET));
        when(documentRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(entity));

        GitProjectionDriftService svc = new GitProjectionDriftService(documentRepository, store, props);
        var report = svc.listContentDrift();

        assertThat(report.storageProvider()).isEqualTo("git");
        assertThat(report.items()).hasSize(1);
        assertThat(report.items().getFirst().documentId()).isEqualTo(DOC);
        assertThat(report.items().getFirst().missingTransclusionTargetIds()).contains(TARGET);
        assertThat(report.procedure()).contains("append-only");
    }

    @Test
    void gitProvider_noDriftWhenProjectionMatchesBlob() {
        StorageProperties props = new StorageProperties();
        props.setProvider("git");
        props.setGitRepositoryPath(tempDir.resolve("repo2").toString());
        props.validate();

        GitDocumentStore store = new GitDocumentStore(
                mock(eu.socle.document.DocumentVersionRepository.class),
                tempDir.resolve("repo2"));
        Map<String, Object> body = composite(TARGET);
        store.createContent(DOC, body, AUTHOR);

        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setTitle("OK");
        entity.setBody(body);
        when(documentRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(entity));

        var report = new GitProjectionDriftService(documentRepository, store, props).listContentDrift();
        assertThat(report.items()).isEmpty();
    }

    private static Map<String, Object> tipTap(String text) {
        return Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text))
                ))
        );
    }

    private static Map<String, Object> composite(UUID targetId) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("type", TransclusionResolver.NODE_TYPE);
        tx.put("attrs", Map.of(TransclusionResolver.ATTR_DOCUMENT_ID, targetId.toString()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(tx));
        return doc;
    }
}
