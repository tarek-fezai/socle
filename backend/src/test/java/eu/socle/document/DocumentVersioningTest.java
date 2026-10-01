// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentDtos.VersionDiffResponse;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentVersioningTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;

    DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, reliabilityScoreService, mock(eu.socle.trash.TrashService.class));
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.findActiveByIdForUpdate(any())).thenAnswer(inv ->
                documentRepository.findActiveById(inv.getArgument(0)));
    }

    @Test
    void update_archivesOldBodyThenIncrementsVersion() {
        DocumentEntity entity = document(DOC, Map.of("blocks", List.of("old")), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        Map<String, Object> newBody = Map.of("blocks", List.of("new"));
        var response = service.update(jwt(), DOC, new UpdateDocumentRequest("Titre", newBody, "fix"));

        ArgumentCaptor<DocumentVersionEntity> versionCap = ArgumentCaptor.forClass(DocumentVersionEntity.class);
        InOrder order = inOrder(versionRepository, documentRepository);
        order.verify(versionRepository).save(versionCap.capture());
        order.verify(documentRepository).save(any(DocumentEntity.class));

        DocumentVersionEntity archived = versionCap.getValue();
        assertThat(archived.getVersionNo()).isEqualTo(1);
        assertThat(archived.getBodySnapshot()).isEqualTo(Map.of("blocks", List.of("old")));
        assertThat(archived.getChangeSummary()).isEqualTo("fix");
        assertThat(archived.getAuthorId()).isEqualTo(USER);
        assertThat(response.currentVersionNo()).isEqualTo(2);
        assertThat(response.body()).isEqualTo(newBody);

        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_VERSION_CREATED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void update_whenDocumentSaveFails_doesNotLeaveCommittedVersionOutsideTransaction() {
        // Même méthode @Transactional : si save(document) échoue, Spring rollback aussi la version.
        // Ici on vérifie l'ordre + que l'exception remonte (pas de succès partiel côté API).
        DocumentEntity entity = document(DOC, Map.of("v", 1), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        when(documentRepository.save(any())).thenThrow(new RuntimeException("constraint"));

        assertThatThrownBy(() -> service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 2))))
                .hasMessageContaining("constraint");

        verify(versionRepository).save(any(DocumentVersionEntity.class));
    }

    @Test
    void listVersions_withoutAccess_returns403() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Accès refusé"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "viewer");

        assertThatThrownBy(() -> service.listVersions(jwt(), DOC, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));

        verify(versionRepository, never()).findByDocumentIdOrderByVersionNoDesc(any(), any());
    }

    @Test
    void diff_handlesDifferentJsonShapes() {
        DocumentVersionEntity v1 = version(1, Map.of("blocks", List.of(Map.of("id", "a", "text", "hello"))));
        DocumentVersionEntity v2 = version(2, Map.of(
                "blocks", List.of(Map.of("id", "a", "text", "hello"), Map.of("id", "b", "text", "world")),
                "meta", Map.of("lang", "fr")));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(document(DOC, Map.of(), 3)));
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "viewer");
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 2)).thenReturn(Optional.of(v2));

        VersionDiffResponse diff = service.diff(jwt(), DOC, 1, 2);

        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(2);
        assertThat(diff.changes()).isNotEmpty();
        assertThat(diff.changes()).anyMatch(c -> "added".equals(c.op()));
        assertThat(diff.changes()).noneMatch(c -> c.path() == null || c.op() == null);
    }

    @Test
    void restore_createsNewVersionWithoutRewritingHistory() {
        DocumentEntity entity = document(DOC, Map.of("blocks", List.of("current")), 3);
        DocumentVersionEntity target = version(1, Map.of("blocks", List.of("old")));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(target));

        var response = service.restore(jwt(), DOC, 1, entity.getCurrentVersionNo());

        ArgumentCaptor<DocumentVersionEntity> versionCap = ArgumentCaptor.forClass(DocumentVersionEntity.class);
        verify(versionRepository).save(versionCap.capture());
        DocumentVersionEntity archived = versionCap.getValue();
        assertThat(archived.getVersionNo()).isEqualTo(3);
        assertThat(archived.getBodySnapshot()).isEqualTo(Map.of("blocks", List.of("current")));
        assertThat(archived.getChangeSummary()).isEqualTo("Restauration de la version 1");

        // Historique existant (v1) non réécrit
        verify(versionRepository, never()).save(argThat(v -> v != null && v.getVersionNo() == 1));

        assertThat(response.body()).isEqualTo(Map.of("blocks", List.of("old")));
        assertThat(response.currentVersionNo()).isEqualTo(4);

        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_VERSION_RESTORED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void bodyDiff_unit_addedRemovedModified() {
        BodyDiff.DiffResult r = BodyDiff.diff(
                1, 2,
                Map.of("a", 1, "b", "x"),
                Map.of("a", 2, "c", true));
        assertThat(r.changes()).extracting(BodyDiff.Change::op)
                .contains("modified", "removed", "added");
    }

    private static DocumentEntity document(UUID id, Map<String, Object> body, int versionNo) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setSpaceId(SPACE);
        d.setTitle("Doc");
        d.setBody(body);
        d.setStatus("brouillon");
        d.setCurrentVersionNo(versionNo);
        return d;
    }

    private static DocumentVersionEntity version(int no, Map<String, Object> body) {
        DocumentVersionEntity v = new DocumentVersionEntity();
        v.setId(UUID.randomUUID());
        v.setDocumentId(DOC);
        v.setVersionNo(no);
        v.setBodySnapshot(body);
        v.setAuthorId(USER);
        v.setCreatedAt(Instant.now());
        return v;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
