package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contenu modifié sur document {@code valide} → {@code en_revue} (update et restore).
 * Document {@code archive} → 409.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentStatusOnMutationTest {

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
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        org.mockito.Mockito.doAnswer(inv -> {
            DocumentEntity e = inv.getArgument(0);
            e.setReliabilityScore(null);
            e.setReliabilityComputedAt(null);
            return null;
        }).when(reliabilityScoreService).clearScoreOnEntity(any());
    }

    @Test
    void update_onValide_setsEnRevue_andClearsReliabilityScoreImmediately() {
        DocumentEntity entity = document("valide", Map.of("v", 1), 2);
        entity.setReliabilityScore(new BigDecimal("87.50"));
        entity.setReliabilityComputedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        var response = service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 2), "edit"));

        assertThat(response.status()).isEqualTo("en_revue");
        assertThat(entity.getStatus()).isEqualTo("en_revue");
        assertThat(entity.getReliabilityScore()).isNull();
        assertThat(entity.getReliabilityComputedAt()).isNull();
        assertThat(response.reliabilityScore()).isNull();
        verify(reliabilityScoreService).clearScoreOnEntity(entity);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_UPDATED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("status_before", "valide");
        assertThat(meta.getValue()).containsEntry("status_after", "en_revue");
    }

    @Test
    void restore_onValide_setsEnRevue_andAuditsStatusTransition() {
        DocumentEntity entity = document("valide", Map.of("blocks", List.of("current")), 3);
        DocumentVersionEntity target = version(1, Map.of("blocks", List.of("old")));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(target));

        var response = service.restore(jwt(), DOC, 1, 3);

        assertThat(response.status()).isEqualTo("en_revue");
        assertThat(entity.getStatus()).isEqualTo("en_revue");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_VERSION_RESTORED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("status_before", "valide");
        assertThat(meta.getValue()).containsEntry("status_after", "en_revue");
    }

    @Test
    void update_onBrouillon_keepsStatus() {
        DocumentEntity entity = document("brouillon", Map.of("v", 1), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        var response = service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 2)));

        assertThat(response.status()).isEqualTo("brouillon");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_UPDATED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("status_before", "brouillon");
        assertThat(meta.getValue()).containsEntry("status_after", "brouillon");
    }

    @Test
    void restore_onEnRevue_keepsStatus() {
        DocumentEntity entity = document("en_revue", Map.of("blocks", List.of("a")), 2);
        DocumentVersionEntity target = version(1, Map.of("blocks", List.of("b")));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(target));

        var response = service.restore(jwt(), DOC, 1, 2);

        assertThat(response.status()).isEqualTo("en_revue");
    }

    @Test
    void update_onArchive_rejectedWith409() {
        DocumentEntity entity = document("archive", Map.of("v", 1), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 2))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(versionRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void restore_onArchive_rejectedWith409() {
        DocumentEntity entity = document("archive", Map.of("v", 1), 2);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 2))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(versionRepository, never()).findByDocumentIdAndVersionNo(any(), any(Integer.class));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void applyBodyMutationStatusRules_isSharedByUpdateAndRestore() {
        DocumentEntity valide = document("valide", Map.of(), 1);
        valide.setReliabilityScore(new BigDecimal("50.00"));
        DocumentService.StatusTransition t = service.applyBodyMutationStatusRules(valide);
        assertThat(t.before()).isEqualTo("valide");
        assertThat(t.after()).isEqualTo("en_revue");
        assertThat(t.changed()).isTrue();
        assertThat(valide.getStatus()).isEqualTo("en_revue");
        assertThat(valide.getReliabilityScore()).isNull();
        verify(reliabilityScoreService).clearScoreOnEntity(valide);
    }

    private static DocumentEntity document(String status, Map<String, Object> body, int versionNo) {
        DocumentEntity d = new DocumentEntity();
        d.setId(DOC);
        d.setSpaceId(SPACE);
        d.setTitle("Doc");
        d.setBody(body);
        d.setStatus(status);
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
