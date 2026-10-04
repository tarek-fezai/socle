// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sauvegarde explicite : exactement une version créée, et le brouillon de l'auteur est consommé. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentDraftUpdateTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final String DELETE_DRAFT = "DELETE FROM document_drafts WHERE document_id = ? AND user_id = ?";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;
    @Mock JdbcTemplate jdbc;

    DocumentService service;
    DocumentEntity entity;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository, userSyncService, authorizationService, auditService,
                reliabilityScoreService, mock(eu.socle.trash.TrashService.class),
                new RelationalDocumentStore(versionRepository), null, null, jdbc, null);
        UserEntity u = new UserEntity();
        u.setId(USER);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.findActiveByIdForUpdate(any())).thenAnswer(inv ->
                documentRepository.findActiveById(inv.getArgument(0)));

        entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setTitle("Doc");
        entity.setBody(Map.of("blocks", List.of("old")));
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(1);
        entity.setCreatedBy(USER);
        entity.setUpdatedBy(USER);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        // Pas de demande en cours → update autorisé.
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("approval_requests"),
                eq(Integer.class), eq(DOC))).thenReturn(0);
    }

    @Test
    void explicitUpdate_createsExactlyOneVersion_andDeletesCallersDraft() {
        var response = service.update(jwt(), DOC,
                new UpdateDocumentRequest("Titre", Map.of("blocks", List.of("new")), "save"));

        verify(versionRepository, times(1)).save(any(DocumentVersionEntity.class));
        assertThat(response.currentVersionNo()).isEqualTo(2);
        verify(jdbc).update(eq(DELETE_DRAFT), eq(DOC), eq(USER));
    }

    @Test
    void failedUpdate_keepsDraft() {
        when(documentRepository.save(any())).thenThrow(new RuntimeException("constraint"));

        assertThatThrownBy(() -> service.update(jwt(), DOC,
                new UpdateDocumentRequest("Titre", Map.of("blocks", List.of("new")), "save")))
                .hasMessageContaining("constraint");

        verify(jdbc, never()).update(eq(DELETE_DRAFT), any(), any());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
