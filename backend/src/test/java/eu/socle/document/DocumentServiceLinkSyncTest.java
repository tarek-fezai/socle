// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.trash.TrashService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * create / update / restore recalculent {@code document_links} dans la même transaction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceLinkSyncTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID TGT = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;
    @Mock DocumentLinkService documentLinkService;
    @Mock JdbcTemplate jdbc;

    DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository,
                userSyncService,
                authorizationService,
                auditService,
                reliabilityScoreService,
                mock(TrashService.class),
                new RelationalDocumentStore(versionRepository),
                null,
                null,
                jdbc,
                documentLinkService);
        UserEntity u = new UserEntity();
        u.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.findActiveByIdForUpdate(any())).thenAnswer(inv ->
                documentRepository.findActiveById(inv.getArgument(0)));
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "editor");
    }

    @Test
    void update_withTransclusion_indexesOutgoingLink() {
        DocumentEntity entity = document(Map.of("type", "doc"), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        Map<String, Object> body = composite(TGT);
        service.update(jwt(), DOC, new UpdateDocumentRequest("Titre", body, null));

        verify(documentLinkService).replaceOutgoingLinks(eq(DOC), eq(SPACE), eq(body));
    }

    @Test
    void update_removingTransclusion_rewritesLinks() {
        DocumentEntity entity = document(composite(TGT), 1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        Map<String, Object> empty = Map.of("type", "doc", "content", List.of());
        service.update(jwt(), DOC, new UpdateDocumentRequest("Titre", empty, null));

        verify(documentLinkService).replaceOutgoingLinks(eq(DOC), eq(SPACE), eq(empty));
    }

    @Test
    void restore_reindexesLinksFromRestoredBody() {
        Map<String, Object> restoredBody = composite(TGT);
        DocumentEntity entity = document(Map.of("type", "doc"), 3);
        DocumentVersionEntity target = new DocumentVersionEntity();
        target.setDocumentId(DOC);
        target.setVersionNo(1);
        target.setBodySnapshot(restoredBody);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(versionRepository.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(target));

        service.restore(jwt(), DOC, 1, null);

        verify(documentLinkService).replaceOutgoingLinks(eq(DOC), eq(SPACE), eq(restoredBody));
    }

    private static DocumentEntity document(Map<String, Object> body, int version) {
        DocumentEntity e = new DocumentEntity();
        e.setId(DOC);
        e.setSpaceId(SPACE);
        e.setTitle("T");
        e.setBody(body);
        e.setCurrentVersionNo(version);
        e.setStatus("brouillon");
        return e;
    }

    private static Map<String, Object> composite(UUID targetId) {
        return Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "transclusion",
                        "attrs", Map.of("documentId", targetId.toString()))));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
