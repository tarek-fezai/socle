// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.storage.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentLinkServiceTest {

    static final UUID SRC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TGT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID SPACE_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock JdbcTemplate jdbc;
    @Mock TransclusionResolver resolver;
    @Mock DocumentStore documentStore;
    @Mock DocumentRepository documentRepository;
    @Mock ExternalReferenceNotify notify;

    DocumentLinkService service;
    AtomicInteger notifyCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        notifyCount.set(0);
        ExternalReferenceNotify counting = (s, t) -> notifyCount.incrementAndGet();
        service = new DocumentLinkService(
                jdbc, resolver, documentStore, documentRepository, counting, new ObjectMapper());
    }

    @Test
    void replaceOutgoing_deletesThenInserts_andNotifiesInterSpace() {
        when(resolver.extractDirectTargets(any())).thenReturn(List.of(TGT));
        when(jdbc.query(contains("SELECT space_id"), any(RowMapper.class), eq(TGT)))
                .thenReturn(List.of(SPACE_B));
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(1);

        Map<String, Object> body = Map.of("type", "doc");
        service.replaceOutgoingLinks(SRC, SPACE_A, body);

        verify(jdbc).update(contains("DELETE"), eq(SRC), eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
        verify(jdbc).update(contains("INSERT"), eq(SRC), eq(TGT), eq(SPACE_A),
                eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
        assertThat(notifyCount.get()).isEqualTo(1);
    }

    @Test
    void replaceOutgoing_clearsLinksWhenNoTargets() {
        when(resolver.extractDirectTargets(any())).thenReturn(List.of());
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(2);

        service.replaceOutgoingLinks(SRC, SPACE_A, Map.of());

        verify(jdbc).update(contains("DELETE"), eq(SRC), eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
        verify(jdbc, never()).update(contains("INSERT"), any(), any(), any(), any());
        assertThat(notifyCount.get()).isZero();
    }

    @Test
    void extractInNestedList_indexedViaResolver() {
        // DocumentLinkService s'appuie sur extractDirectTargets (profondeur listes)
        UUID nested = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(resolver.extractDirectTargets(any())).thenReturn(List.of(nested));
        when(jdbc.query(contains("SELECT space_id"), any(RowMapper.class), eq(nested)))
                .thenReturn(List.of(SPACE_A));
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(0);

        service.replaceOutgoingLinks(SRC, SPACE_A, nestedListBody(nested));

        verify(jdbc).update(contains("INSERT"), eq(SRC), eq(nested), eq(SPACE_A),
                eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
    }

    @Test
    void backfill_idempotentSecondRunSkipped() {
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(1);

        var report = service.backfill(false);

        assertThat(report.skipped()).isTrue();
        verify(documentRepository, never()).findAllByOrderByUpdatedAtDesc();
    }

    @Test
    void backfill_forceRewritesAllActive() {
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(1);
        DocumentEntity doc = new DocumentEntity();
        doc.setId(SRC);
        doc.setSpaceId(SPACE_A);
        doc.setBody(Map.of("type", "doc"));
        when(documentRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(doc));
        when(documentStore.readCurrentContent(eq(SRC), any())).thenReturn(Map.of("type", "doc"));
        when(resolver.extractDirectTargets(any())).thenReturn(List.of());
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(0);
        when(jdbc.update(contains("INSERT INTO authz_migrations"), any(), any())).thenReturn(1);

        var report = service.backfill(true);

        assertThat(report.skipped()).isFalse();
        assertThat(report.documentsScanned()).isEqualTo(1);
        assertThat(report.rewritten()).isEqualTo(1);
    }

    @Test
    void updateSourceSpace_updatesColumn() {
        when(jdbc.update(contains("UPDATE document_links"), eq(SPACE_B), eq(SRC))).thenReturn(3);
        service.updateSourceSpace(SRC, SPACE_B);
        verify(jdbc).update(contains("UPDATE document_links"), eq(SPACE_B), eq(SRC));
    }

    @Test
    void replaceOutgoing_thenClear_removesArcs() {
        when(resolver.extractDirectTargets(any())).thenReturn(List.of(TGT));
        when(jdbc.query(contains("SELECT space_id"), any(RowMapper.class), eq(TGT)))
                .thenReturn(List.of(SPACE_A));
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("DELETE"), any(), any())).thenReturn(1);

        service.replaceOutgoingLinks(SRC, SPACE_A, Map.of("type", "doc"));

        when(resolver.extractDirectTargets(any())).thenReturn(List.of());
        service.replaceOutgoingLinks(SRC, SPACE_A, Map.of("type", "doc"));

        verify(jdbc, org.mockito.Mockito.times(2))
                .update(contains("DELETE"), eq(SRC), eq(DocumentLinkService.LINK_TYPE_TRANSCLUSION));
    }

    private static Map<String, Object> nestedListBody(UUID targetId) {
        Map<String, Object> transclusion = new HashMap<>();
        transclusion.put("type", "transclusion");
        transclusion.put("attrs", Map.of("documentId", targetId.toString()));
        Map<String, Object> listItem = Map.of("type", "listItem", "content", List.of(transclusion));
        Map<String, Object> bullet = Map.of("type", "bulletList", "content", List.of(listItem));
        return Map.of("type", "doc", "content", List.of(bullet));
    }
}
