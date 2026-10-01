// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Préalable soft-lock : en relational, deux updates sur la même version de départ
 * → la seconde reçoit 409 (pas d'écrasement silencieux).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentOptimisticConcurrencyTest {

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
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.findActiveByIdForUpdate(any())).thenAnswer(inv ->
                documentRepository.findActiveById(inv.getArgument(0)));
    }

    @Test
    void relational_secondUpdateWithStaleExpectedVersion_returns409() {
        DocumentEntity entity = document(1, Map.of("v", 1));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 2), "a", 1));
        assertThat(entity.getCurrentVersionNo()).isEqualTo(2);

        assertThatThrownBy(() ->
                service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("v", 3), "b", 1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("attendu v1");
                    assertThat(rse.getReason()).contains("actuel v2");
                });

        assertThat(entity.getCurrentVersionNo()).isEqualTo(2);
        assertThat(entity.getBody()).isEqualTo(Map.of("v", 2));
    }

    @Test
    void softLockDoesNotBlockUpdate_evenWhenAnotherUserWouldHoldAdvisoryLock() {
        // Le soft-lock n'intervient pas dans DocumentService.update — sauvegarde OK.
        DocumentEntity entity = document(3, Map.of("x", 1));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        var response = service.update(jwt(), DOC, new UpdateDocumentRequest("T", Map.of("x", 2), null, 3));
        assertThat(response.currentVersionNo()).isEqualTo(4);
    }

    @Test
    void restore_withStaleExpectedVersion_returns409() {
        DocumentEntity entity = document(5, Map.of("v", "current"));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 4))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("attendu v4");
                    assertThat(rse.getReason()).contains("actuel v5");
                });

        assertThat(entity.getCurrentVersionNo()).isEqualTo(5);
        verify(versionRepository, never()).save(any());
    }

    private static DocumentEntity document(int version, Map<String, Object> body) {
        DocumentEntity d = new DocumentEntity();
        d.setId(DOC);
        d.setSpaceId(SPACE);
        d.setTitle("Doc");
        d.setBody(body);
        d.setStatus("brouillon");
        d.setCurrentVersionNo(version);
        return d;
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
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
