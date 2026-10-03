// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.AuthorizationService.DocumentPermissionChecks;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.PersonRef;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Réponse GET document : bloc {@code permissions} (un seul BatchCheck) et {@link PersonRef}
 * pour createdBy / updatedBy / owner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentResponsePermissionsAndPeopleTest {

    static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID EDITOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SPACE_OWNER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID AUTHOR = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID GONE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;
    @Mock JdbcTemplate jdbc;

    /** Constructeur sans JDBC : politique de commentaire par défaut « members », pas de personnes. */
    DocumentService serviceWithoutJdbc;
    /** Constructeur avec JDBC simulé pour owner / users. */
    DocumentService serviceWithJdbc;

    UserEntity currentUser;
    DocumentEntity entity;

    @BeforeEach
    void setUp() {
        serviceWithoutJdbc = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, reliabilityScoreService, mock(eu.socle.trash.TrashService.class));
        serviceWithJdbc = new DocumentService(
                documentRepository, userSyncService, authorizationService, auditService,
                reliabilityScoreService, mock(eu.socle.trash.TrashService.class),
                new eu.socle.storage.RelationalDocumentStore(versionRepository),
                null, null, jdbc, null, null);

        entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setTitle("Doc");
        entity.setBody(Map.of("type", "doc"));
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        actAs(VIEWER);
    }

    void actAs(UUID id) {
        currentUser = new UserEntity();
        currentUser.setId(id);
        when(userSyncService.syncFromJwt(any())).thenReturn(currentUser);
    }

    // ---------------------------------------------------------------- permissions

    @Test
    void viewer_cannotEditNorPublish_butCanComment() {
        when(authorizationService.batchCheckDocumentPermissions(VIEWER, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(false, false, true, false, true));

        DocumentResponse r = serviceWithoutJdbc.get(jwt(), DOC);

        assertThat(r.permissions().canEdit()).isFalse();
        assertThat(r.permissions().canPublish()).isFalse();
        assertThat(r.permissions().canManageAccess()).isFalse();
        assertThat(r.permissions().canManageAttestations()).isFalse();
        assertThat(r.permissions().canComment()).isTrue();
    }

    @Test
    void editor_canEditAndPublishDraft_butNotManageAccessOrAttestations() {
        actAs(EDITOR);
        when(authorizationService.batchCheckDocumentPermissions(EDITOR, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(false, true, true, false, true));

        DocumentResponse r = serviceWithoutJdbc.get(jwt(), DOC);

        assertThat(r.permissions().canEdit()).isTrue();
        assertThat(r.permissions().canPublish()).isTrue();
        assertThat(r.permissions().canComment()).isTrue();
        assertThat(r.permissions().canManageAccess()).isFalse();
        assertThat(r.permissions().canManageAttestations()).isFalse();
    }

    @Test
    void editor_cannotPublishWhenNotBrouillon() {
        actAs(EDITOR);
        entity.setStatus("valide");
        when(authorizationService.batchCheckDocumentPermissions(EDITOR, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(false, true, true, false, true));

        DocumentResponse r = serviceWithoutJdbc.get(jwt(), DOC);

        assertThat(r.permissions().canEdit()).isTrue();
        assertThat(r.permissions().canPublish()).isFalse();
    }

    @Test
    void spaceOwner_canManageAttestationsAndAccess() {
        actAs(SPACE_OWNER);
        when(authorizationService.batchCheckDocumentPermissions(SPACE_OWNER, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(true, true, true, true, true));

        DocumentResponse r = serviceWithoutJdbc.getResolved(jwt(), DOC);

        assertThat(r.permissions().canManageAttestations()).isTrue();
        assertThat(r.permissions().canManageAccess()).isTrue();
        assertThat(r.permissions().canEdit()).isTrue();
        assertThat(r.permissions().canPublish()).isTrue();
    }

    @Test
    void ownerWithoutSpaceViewer_cannotPublish_andPermissionsUseSingleBatchCheck() {
        actAs(SPACE_OWNER);
        when(authorizationService.batchCheckDocumentPermissions(SPACE_OWNER, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(true, true, true, false, false));

        DocumentResponse r = serviceWithoutJdbc.get(jwt(), DOC);

        assertThat(r.permissions().canPublish()).isFalse();
        assertThat(r.permissions().canManageAccess()).isTrue();
        assertThat(r.permissions().canManageAttestations()).isFalse();
        verify(authorizationService, times(1)).batchCheckDocumentPermissions(SPACE_OWNER, DOC, SPACE);
        // Aucun Check unitaire pour construire permissions (seul viewer de garde passe par requireDocumentRelation).
        verify(authorizationService, org.mockito.Mockito.never())
                .hasRelation(any(), anyString(), any(), anyString());
    }

    @Test
    void commentPolicyAllReaders_documentViewerWithoutSpaceMembership_canComment() {
        stubSpaceContext("all_readers", null);
        when(authorizationService.batchCheckDocumentPermissions(VIEWER, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(false, false, true, false, false));

        assertThat(serviceWithJdbc.get(jwt(), DOC).permissions().canComment()).isTrue();
    }

    @Test
    void commentPolicyMembers_documentViewerWithoutSpaceMembership_cannotComment() {
        stubSpaceContext("members", null);
        when(authorizationService.batchCheckDocumentPermissions(VIEWER, DOC, SPACE))
                .thenReturn(new DocumentPermissionChecks(false, false, true, false, false));

        assertThat(serviceWithJdbc.get(jwt(), DOC).permissions().canComment()).isFalse();
    }

    @Test
    void permissionsDefaultToNone_whenAuthorizationReturnsNothing() {
        DocumentResponse r = serviceWithoutJdbc.get(jwt(), DOC);

        assertThat(r.permissions()).isEqualTo(DocumentDtos.DocumentPermissions.NONE);
    }

    // ---------------------------------------------------------------- personnes

    @Test
    void nullCreatedByAndUpdatedBy_stayNull() {
        entity.setCreatedBy(null);
        entity.setUpdatedBy(null);
        stubSpaceContext("members", null);

        DocumentResponse r = serviceWithJdbc.get(jwt(), DOC);

        assertThat(r.createdBy()).isNull();
        assertThat(r.updatedBy()).isNull();
        assertThat(r.owner()).isNull();
        // Aucun id à résoudre → pas de requête users.
        verify(jdbc, org.mockito.Mockito.never())
                .query(contains("FROM users"), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void resolvedAuthorsAndOwner_comeFromSingleGroupedUsersQuery() {
        entity.setCreatedBy(AUTHOR);
        entity.setUpdatedBy(AUTHOR);
        stubSpaceContext("members", SPACE_OWNER);
        stubUsers(
                new UserRow(AUTHOR, "Alice Martin", null, "active"),
                new UserRow(SPACE_OWNER, "Olivier Durand", "OD", "active"));

        DocumentResponse r = serviceWithJdbc.get(jwt(), DOC);

        assertThat(r.createdBy()).isEqualTo(new PersonRef(AUTHOR, "Alice Martin", "AM"));
        assertThat(r.updatedBy()).isEqualTo(r.createdBy());
        assertThat(r.owner()).isEqualTo(new PersonRef(SPACE_OWNER, "Olivier Durand", "OD"));
        verify(jdbc, times(1)).query(
                contains("FROM users"), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void anonymizedUser_isShownAsUtilisateurSupprime() {
        entity.setCreatedBy(GONE);
        entity.setUpdatedBy(AUTHOR);
        stubSpaceContext("members", null);
        stubUsers(
                new UserRow(GONE, "Jean Dupont", "JD", "anonymized"),
                new UserRow(AUTHOR, "Alice Martin", "AM", "active"));

        DocumentResponse r = serviceWithJdbc.get(jwt(), DOC);

        assertThat(r.createdBy()).isEqualTo(new PersonRef(GONE, "Utilisateur supprimé", "US"));
        assertThat(r.updatedBy().displayName()).isEqualTo("Alice Martin");
    }

    @Test
    void authorWithoutUsersRow_isShownAsUtilisateurSupprime() {
        entity.setCreatedBy(GONE);
        stubSpaceContext("members", null);
        stubUsers(); // aucune ligne

        DocumentResponse r = serviceWithJdbc.get(jwt(), DOC);

        assertThat(r.createdBy().id()).isEqualTo(GONE);
        assertThat(r.createdBy().displayName()).isEqualTo(PersonRef.DELETED_LABEL);
        assertThat(r.updatedBy()).isNull();
    }

    @Test
    void personRefInitials() {
        assertThat(PersonRef.initialsOf("Alice Martin")).isEqualTo("AM");
        assertThat(PersonRef.initialsOf("Cher")).isEqualTo("CH");
        assertThat(PersonRef.initialsOf("Jean Pierre de la Fontaine")).isEqualTo("JF");
        assertThat(PersonRef.initialsOf(" ")).isEqualTo("?");
        assertThat(PersonRef.deleted(GONE).initials()).isEqualTo("US");
    }

    // ---------------------------------------------------------------- helpers

    private void stubSpaceContext(String policy, UUID ownerId) {
        ResultSet rs = mock(ResultSet.class);
        try {
            when(rs.next()).thenReturn(true);
            when(rs.getString("comment_policy")).thenReturn(policy);
            when(rs.getObject("owner_id")).thenReturn(ownerId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(jdbc.query(contains("space_owners"), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    ResultSetExtractor<?> ex = inv.getArgument(1);
                    return ex.extractData(rs);
                });
    }

    private record UserRow(UUID id, String displayName, String initials, String status) {}

    private void stubUsers(UserRow... rows) {
        List<UserRow> list = List.of(rows);
        ResultSet rs = mock(ResultSet.class);
        int[] idx = {-1};
        try {
            when(rs.next()).thenAnswer(inv -> ++idx[0] < list.size());
            when(rs.getObject("id")).thenAnswer(inv -> list.get(idx[0]).id());
            when(rs.getString("display_name")).thenAnswer(inv -> list.get(idx[0]).displayName());
            when(rs.getString("avatar_initials")).thenAnswer(inv -> list.get(idx[0]).initials());
            when(rs.getString("status")).thenAnswer(inv -> list.get(idx[0]).status());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(jdbc.query(contains("FROM users"), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    idx[0] = -1;
                    ResultSetExtractor<?> ex = inv.getArgument(1);
                    return ex.extractData(rs);
                });
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("sub")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
