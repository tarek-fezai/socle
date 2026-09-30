package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.trash.TrashService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSoftDeleteFilterTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID TRASHED = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;
    @Mock TrashService trashService;

    @Test
    void list_usesActiveOnlyQuery() {
        DocumentService service = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, reliabilityScoreService, trashService);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(authorizationService.countPreselectedDocuments(eq(USER), any())).thenReturn(1);
        when(authorizationService.preselectDocumentIdsPage(eq(USER), any(), anyInt(), anyInt()))
                .thenReturn(List.of(DOC));
        when(authorizationService.filterByDocumentViewer(eq(USER), any(), any()))
                .thenReturn(List.of(DOC));

        DocumentEntity a = document(DOC, "Actif");
        when(documentRepository.findAllActiveByIdIn(List.of(DOC))).thenReturn(List.of(a));

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        var page = service.list(jwt, 20, 0);

        assertThat(page.results()).hasSize(1);
        assertThat(page.results().getFirst().id()).isEqualTo(DOC);
        assertThat(page.totalIsEstimate()).isTrue();
        verify(documentRepository).findAllActiveByIdIn(List.of(DOC));
    }

    @Test
    void get_returns404ForSoftDeleted() {
        DocumentService service = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, reliabilityScoreService, trashService);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        org.mockito.Mockito.doNothing().when(authorizationService)
                .requireDocumentRelation(any(), any(), any());
        when(documentRepository.findActiveById(TRASHED)).thenReturn(java.util.Optional.empty());

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThatThrownBy(() -> service.get(jwt, TRASHED))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private static DocumentEntity document(UUID id, String title) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setTitle(title);
        e.setBody(java.util.Map.of());
        e.setStatus("brouillon");
        e.setCurrentVersionNo(1);
        e.touch();
        return e;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        return u;
    }
}
