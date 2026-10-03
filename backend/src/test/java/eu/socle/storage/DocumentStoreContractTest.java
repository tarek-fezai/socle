// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentVersionEntity;
import eu.socle.document.DocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrat commun create/update/diff/restore — exécuté contre relational ET git.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class DocumentStoreContractTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ARCHIVER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CONTENT_AUTHOR = UUID.fromString("33333333-3333-3333-3333-333333333333");

    DocumentStore store;
    DocumentVersionRepository versions;
    Map<String, DocumentVersionEntity> versionRows;

    abstract DocumentStore createStore(DocumentVersionRepository versions, Path tempDir);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        versionRows = new ConcurrentHashMap<>();
        versions = mock(DocumentVersionRepository.class);
        when(versions.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> {
            DocumentVersionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            if (e.getCreatedAt() == null) {
                e.setCreatedAt(Instant.now());
            }
            versionRows.put(e.getDocumentId() + ":" + e.getVersionNo(), e);
            return e;
        });
        when(versions.findByDocumentIdAndVersionNo(any(), anyInt())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            int no = inv.getArgument(1);
            return Optional.ofNullable(versionRows.get(id + ":" + no));
        });
        when(versions.findByDocumentIdOrderByVersionNoDesc(eq(DOC), any(Pageable.class))).thenAnswer(inv -> {
            Pageable p = inv.getArgument(1);
            List<DocumentVersionEntity> all = versionRows.values().stream()
                    .filter(v -> DOC.equals(v.getDocumentId()))
                    .sorted(Comparator.comparingInt(DocumentVersionEntity::getVersionNo).reversed())
                    .toList();
            int start = (int) p.getOffset();
            int end = Math.min(start + p.getPageSize(), all.size());
            List<DocumentVersionEntity> slice = start >= all.size() ? List.of() : all.subList(start, end);
            return new PageImpl<>(new ArrayList<>(slice), p, all.size());
        });
        when(versions.countByDocumentId(eq(DOC))).thenAnswer(inv ->
                versionRows.values().stream().filter(v -> DOC.equals(v.getDocumentId())).count());
        store = createStore(versions, tempDir);
    }

    @Test
    void create_thenUpdate_archivesPreviousVersion() {
        Map<String, Object> v1 = tipTap("Version une");
        String head = store.createContent(DOC, v1, AUTHOR);

        store.archiveVersion(DOC, 1, v1, AUTHOR, AUTHOR, "edit");
        Map<String, Object> v2 = tipTap("Version deux");
        store.writeCurrentContent(DOC, v2, AUTHOR, AUTHOR, "edit", head);

        Optional<DocumentStore.StoredVersion> archived = store.findVersion(DOC, 1);
        assertThat(archived).isPresent();
        assertThat(archived.get().bodySnapshot()).isEqualTo(v1);
        assertThat(archived.get().authorId()).isEqualTo(AUTHOR);
        assertThat(archived.get().archivedBy()).isEqualTo(AUTHOR);
        assertThat(store.listVersions(DOC, 0, 10).getTotalElements()).isEqualTo(1);
    }

    @Test
    void archiveVersion_storesContentAuthorDistinctFromArchiver() {
        Map<String, Object> v1 = tipTap("Contenu Alice");
        String head = store.createContent(DOC, v1, CONTENT_AUTHOR);

        store.archiveVersion(DOC, 1, v1, CONTENT_AUTHOR, ARCHIVER, "Soumission pour approbation");
        store.writeCurrentContent(DOC, v1, CONTENT_AUTHOR, ARCHIVER, "Soumission pour approbation", head);

        Optional<DocumentStore.StoredVersion> archived = store.findVersion(DOC, 1);
        assertThat(archived).isPresent();
        assertThat(archived.get().authorId()).isEqualTo(CONTENT_AUTHOR);
        assertThat(archived.get().archivedBy()).isEqualTo(ARCHIVER);
    }

    @Test
    void diff_betweenTwoArchivedVersions_returnsChanges() {
        Map<String, Object> a = tipTap("Alpha");
        Map<String, Object> b = tipTap("Beta");
        String head1 = store.createContent(DOC, a, AUTHOR);
        store.archiveVersion(DOC, 1, a, AUTHOR, AUTHOR, "v1");
        String head2 = store.writeCurrentContent(DOC, b, AUTHOR, AUTHOR, "to-beta", head1);
        store.archiveVersion(DOC, 2, b, AUTHOR, AUTHOR, "v2");
        store.writeCurrentContent(DOC, tipTap("Gamma"), AUTHOR, AUTHOR, "to-gamma", head2);

        DocumentStore.VersionDiffResult diff = store.diff(DOC, 1, 2);
        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(2);
        assertThat(diff.changes()).isNotEmpty();
    }

    @Test
    void restore_loadsArchivedBody_withoutRewritingOldVersion() {
        Map<String, Object> a = tipTap("Original");
        Map<String, Object> b = tipTap("Modifié");
        String head1 = store.createContent(DOC, a, AUTHOR);
        store.archiveVersion(DOC, 1, a, AUTHOR, AUTHOR, "v1");
        String head2 = store.writeCurrentContent(DOC, b, AUTHOR, AUTHOR, "v2", head1);

        Map<String, Object> restored = store.loadVersionBody(DOC, 1);
        assertThat(TipTapMarkdown.toMarkdown(restored)).containsIgnoringCase("Original");

        store.archiveVersion(DOC, 2, b, AUTHOR, AUTHOR, "Restauration de la version 1");
        store.writeCurrentContent(DOC, restored, AUTHOR, AUTHOR, "Restauration de la version 1", head2);

        assertThat(store.findVersion(DOC, 1)).isPresent();
        assertThat(store.findVersion(DOC, 1).get().bodySnapshot()).isEqualTo(a);
        assertThat(store.listVersions(DOC, 0, 10).getTotalElements()).isEqualTo(2);
    }

    @Test
    void createUpdate_preservesTransclusionAndUnknownNodes_currentAndArchived() {
        UUID target = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        Map<String, Object> warningPara = new LinkedHashMap<>();
        warningPara.put("type", "paragraph");
        warningPara.put("content", List.of(Map.of("type", "text", "text", "Attention")));

        Map<String, Object> callout = new LinkedHashMap<>();
        callout.put("type", "callout");
        callout.put("attrs", Map.of("tone", "warning"));
        callout.put("content", List.of(warningPara));

        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("type", "transclusion");
        tx.put("attrs", Map.of("documentId", target.toString()));

        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("type", "doc");
        v1.put("content", List.of(
                Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "Intro"))),
                tx,
                callout
        ));

        String head = store.createContent(DOC, v1, AUTHOR);
        // Relational : contenu courant = projection (param). Git : blob HEAD (projection ignorée).
        assertThat(normalizeDoc(store.readCurrentContent(DOC, v1))).isEqualTo(normalizeDoc(v1));

        store.archiveVersion(DOC, 1, v1, AUTHOR, AUTHOR, "edit");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orig = (List<Map<String, Object>>) v1.get("content");
        List<Map<String, Object>> blocks = new ArrayList<>(orig);
        blocks.add(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "Suite"))));
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("type", "doc");
        v2.put("content", blocks);
        store.writeCurrentContent(DOC, v2, AUTHOR, AUTHOR, "edit", head);

        assertThat(normalizeDoc(store.readCurrentContent(DOC, v2))).isEqualTo(normalizeDoc(v2));

        Optional<DocumentStore.StoredVersion> archived = store.findVersion(DOC, 1);
        assertThat(archived).isPresent();
        assertThat(normalizeDoc(archived.get().bodySnapshot())).isEqualTo(normalizeDoc(v1));
        assertThat(normalizeDoc(store.loadVersionBody(DOC, 1))).isEqualTo(normalizeDoc(v1));
    }

    private static Object normalizeDoc(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalizeDoc(v)));
            return out;
        }
        if (o instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object e : list) {
                out.add(normalizeDoc(e));
            }
            return out;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o;
    }

    static Map<String, Object> tipTap(String text) {
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
