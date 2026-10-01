// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.authz.AuthorizationService;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.DocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * À l'écriture, les nœuds transclusion sont stockés sans content résolu ;
 * document_links n'indexe que la cible directe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransclusionStorageNormalizationTest {

    static final UUID SRC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TGT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID NESTED = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID SPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock JdbcTemplate jdbc;
    @Mock DocumentStore documentStore;
    @Mock DocumentRepository documentRepository;
    @Mock ExternalReferenceNotify notify;
    @Mock AuthorizationService authorizationService;
    @Mock ExternalReferencePolicy policy;

    @Test
    void normalizeForStorage_stripsResolvedContent() {
        TransclusionResolver resolver = new TransclusionResolver(
                documentRepository, documentStore, authorizationService, policy, (a, b) -> { });

        Map<String, Object> resolvedInner = Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "transclusion",
                        "attrs", Map.of("documentId", NESTED.toString()),
                        "content", List.of(Map.of("type", "paragraph")))));

        Map<String, Object> tx = new HashMap<>();
        tx.put("type", "transclusion");
        tx.put("attrs", Map.of(
                "documentId", TGT.toString(),
                "accessible", true,
                "title", "Secret"));
        tx.put("content", List.of(resolvedInner));

        Map<String, Object> body = Map.of("type", "doc", "content", List.of(tx));
        Map<String, Object> stored = resolver.normalizeForStorage(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<?>) stored.get("content")).get(0);
        assertThat(node.get("type")).isEqualTo("transclusion");
        assertThat(node).doesNotContainKey("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) node.get("attrs");
        assertThat(attrs).containsOnlyKeys("documentId");
        assertThat(attrs.get("documentId")).isEqualTo(TGT.toString());

        assertThat(resolver.extractDirectTargets(stored)).containsExactly(TGT);
    }

    @Test
    void replaceOutgoingLinks_indexesOnlyDirectTarget_notNestedInResolvedContent() {
        TransclusionResolver resolver = new TransclusionResolver(
                documentRepository, documentStore, authorizationService, policy, (a, b) -> { });
        DocumentLinkService links = new DocumentLinkService(
                jdbc, resolver, documentStore, documentRepository, notify, new ObjectMapper());

        Map<String, Object> nestedTx = Map.of(
                "type", "transclusion",
                "attrs", Map.of("documentId", NESTED.toString()),
                "content", List.of(Map.of("type", "paragraph", "content", List.of())));
        Map<String, Object> tx = new HashMap<>();
        tx.put("type", "transclusion");
        tx.put("attrs", Map.of("documentId", TGT.toString()));
        tx.put("content", List.of(nestedTx));
        Map<String, Object> dirty = Map.of("type", "doc", "content", List.of(tx));

        Map<String, Object> clean = resolver.normalizeForStorage(dirty);

        when(jdbc.query(contains("SELECT space_id"), any(RowMapper.class), eq(TGT)))
                .thenReturn(List.of(SPACE));
        when(jdbc.update(any(String.class), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(0);

        links.replaceOutgoingLinks(SRC, SPACE, clean);

        verify(jdbc).update(contains("INSERT"), eq(SRC), eq(TGT), eq(SPACE),
                eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
        verify(jdbc, never()).update(contains("INSERT"), eq(SRC), eq(NESTED), any(), any());
    }
}
