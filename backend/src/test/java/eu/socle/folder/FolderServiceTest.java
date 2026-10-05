// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.audit.AuditActions;
import eu.socle.folder.FolderDtos.CreateFolderRequest;
import eu.socle.folder.FolderDtos.MoveDocumentRequest;
import eu.socle.folder.FolderDtos.MoveFolderRequest;
import eu.socle.folder.FolderDtos.UpdateFolderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static eu.socle.folder.FolderTestSupport.OTHER_SPACE;
import static eu.socle.folder.FolderTestSupport.SPACE;
import static eu.socle.folder.FolderTestSupport.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Règles métier de {@link FolderService} : profondeur max, cycles, unicité de nom,
 * déplacement inter-espace et autorisations (OpenFGA mocké).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderServiceTest {

    static final UUID F1 = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    static final UUID F2 = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    static final UUID F3 = UUID.fromString("f0000000-0000-0000-0000-000000000003");
    static final UUID FOREIGN = UUID.fromString("f0000000-0000-0000-0000-0000000000ff");
    static final UUID DOC = UUID.fromString("d0000000-0000-0000-0000-000000000001");

    FolderTestSupport fx;
    Jwt jwt;

    @BeforeEach
    void setUp() {
        fx = new FolderTestSupport();
        jwt = FolderTestSupport.jwt();
        doNothing().when(fx.authorizationService).requireDocumentRelation(any(), any(), any());
        doNothing().when(fx.authorizationService).requireFolderRelation(any(), any(), any());
        doNothing().when(fx.authorizationService).requireSpaceRelation(any(), any(), any());
        when(fx.authorizationService.hasRelation(any(), any(), any(), any())).thenReturn(false);
    }

    // ── profondeur max ───────────────────────────────────────────────

    @Test
    void create_beyondMaxDepth_conflict() {
        fx.folder(F1, SPACE, null, "N1").folder(F2, SPACE, F1, "N2");
        FolderService service = fx.service(2);

        assertThatThrownBy(() -> service.create(jwt, new CreateFolderRequest(SPACE, F2, "N3", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(409);
                    assertThat(rse.getReason()).containsIgnoringCase("profondeur");
                });
        verify(fx.jdbc, never()).update(contains("INSERT INTO folders"),
                any(), any(), any(), any(), any(), any());
        verify(fx.authorizationService, never()).provisionFolderAccess(any(), any(), any());
    }

    @Test
    void create_atExactlyMaxDepth_isAccepted() {
        fx.folder(F1, SPACE, null, "N1");
        FolderService service = fx.service(2);

        var view = service.create(jwt, new CreateFolderRequest(SPACE, F1, "N2", null));

        assertThat(view.parentFolderId()).isEqualTo(F1);
        assertThat(view.name()).isEqualTo("N2");
        verify(fx.jdbc).update(contains("INSERT INTO folders"),
                any(), eq(SPACE), eq(F1), eq("N2"), any(), eq(USER));
        verify(fx.authorizationService).provisionFolderAccess(any(UUID.class), eq("folder"), eq(F1));
    }

    @Test
    void moveFolder_subtreeWouldExceedMaxDepth_conflict() {
        // F1 > F2 (sous-arbre de hauteur 1) ; cible : F3 déjà à la profondeur 2 (maxDepth = 3)
        UUID root = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
        UUID mid = UUID.fromString("f0000000-0000-0000-0000-0000000000a2");
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, F1, "A2")
                .folder(root, SPACE, null, "R").folder(mid, SPACE, root, "R2");
        FolderService service = fx.service(3);

        // R > R2 > F1 > F2 = profondeur 4 > 3
        assertThatThrownBy(() -> service.move(jwt, F1, new MoveFolderRequest(mid, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
        verify(fx.authorizationService, never()).reparentFolder(any(), any(), any(), any(), any());
    }

    // ── cycles ───────────────────────────────────────────────────────

    @Test
    void moveFolder_intoDescendant_conflict() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, F1, "B").folder(F3, SPACE, F2, "C");
        FolderService service = fx.service(5);

        assertThatThrownBy(() -> service.move(jwt, F1, new MoveFolderRequest(F3, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(409);
                    assertThat(rse.getReason()).containsIgnoringCase("descendant");
                });
        verify(fx.jdbc, never()).update(contains("UPDATE folders"), any(), any(), any());
        verify(fx.authorizationService, never()).reparentFolder(any(), any(), any(), any(), any());
        verify(fx.auditService, never()).record(any(), anyBoolean(), eq(AuditActions.FOLDER_MOVED),
                anyString(), any(), anyMap(), any());
    }

    @Test
    void moveFolder_intoItself_conflict() {
        fx.folder(F1, SPACE, null, "A");
        FolderService service = fx.service(5);

        assertThatThrownBy(() -> service.move(jwt, F1, new MoveFolderRequest(F1, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
        verify(fx.authorizationService, never()).reparentFolder(any(), any(), any(), any(), any());
    }

    @Test
    void moveFolder_crossSpace_conflict() {
        fx.folder(F1, SPACE, null, "A").folder(FOREIGN, OTHER_SPACE, null, "Ailleurs");
        FolderService service = fx.service(5);

        assertThatThrownBy(() -> service.move(jwt, F1, new MoveFolderRequest(FOREIGN, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
    }

    @Test
    void moveFolder_validMove_reparentsInOpenFga() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, null, "B");
        FolderService service = fx.service(5);

        service.move(jwt, F1, new MoveFolderRequest(F2, null));

        verify(fx.authorizationService).reparentFolder(F1, "space", SPACE, "folder", F2);
        verify(fx.auditService).record(eq(USER), eq(false), eq(AuditActions.FOLDER_MOVED),
                eq("folder"), eq(F1), anyMap(), isNull());
    }

    // ── unicité de nom ───────────────────────────────────────────────

    @Test
    void create_duplicateNameAtSameLevel_conflict() {
        fx.folder(F1, SPACE, null, "Procédures");
        FolderService service = fx.service(5);
        doThrow(new DataIntegrityViolationException("uq_folders_sibling_name"))
                .when(fx.jdbc).update(contains("INSERT INTO folders"),
                        any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.create(jwt, new CreateFolderRequest(SPACE, null, "Procédures", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(409);
                    assertThat(rse.getReason()).containsIgnoringCase("existe déjà");
                });
        verify(fx.authorizationService, never()).provisionFolderAccess(any(), any(), any());
    }

    @Test
    void rename_toExistingSiblingName_conflict() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, null, "B");
        FolderService service = fx.service(5);
        doThrow(new DataIntegrityViolationException("uq_folders_sibling_name"))
                .when(fx.jdbc).update(contains("UPDATE folders"), any(), any(), any());

        assertThatThrownBy(() -> service.update(jwt, F2, new UpdateFolderRequest("A", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
        verify(fx.auditService, never()).record(any(), anyBoolean(), eq(AuditActions.FOLDER_RENAMED),
                anyString(), any(), anyMap(), any());
    }

    @Test
    void moveFolder_nameCollisionAtDestination_conflict() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, null, "B");
        FolderService service = fx.service(5);
        doThrow(new DataIntegrityViolationException("uq_folders_sibling_name"))
                .when(fx.jdbc).update(contains("UPDATE folders"), any(), any(), any());

        assertThatThrownBy(() -> service.move(jwt, F1, new MoveFolderRequest(F2, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
        verify(fx.authorizationService, never()).reparentFolder(any(), any(), any(), any(), any());
    }

    // ── document inter-espace ────────────────────────────────────────

    @Test
    void moveDocument_crossSpace_conflict() {
        fx.folder(FOREIGN, OTHER_SPACE, null, "Ailleurs").doc(DOC, SPACE, null, "Doc");
        FolderService service = fx.service(5);

        assertThatThrownBy(() -> service.moveDocument(jwt, DOC, new MoveDocumentRequest(FOREIGN, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
        verify(fx.jdbc, never()).update(contains("UPDATE documents"), any(), any(), any());
        verify(fx.authorizationService, never()).reparentDocument(any(), any(), any(), any());
    }

    @Test
    void moveDocument_sameSpace_updatesFolderAndReparents() {
        fx.folder(F1, SPACE, null, "A").doc(DOC, SPACE, null, "Doc");
        FolderService service = fx.service(5);

        Map<String, Object> out = service.moveDocument(jwt, DOC, new MoveDocumentRequest(F1, null));

        assertThat(out).containsEntry("folderId", F1).containsEntry("spaceId", SPACE);
        verify(fx.jdbc).update(contains("UPDATE documents"), eq(F1), any(), eq(DOC));
        verify(fx.authorizationService).reparentDocument(DOC, "space:" + SPACE, "folder:" + F1, "space");
        verify(fx.auditService).record(eq(USER), eq(false), eq(AuditActions.DOCUMENT_MOVED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    // ── autorisations ────────────────────────────────────────────────

    @Test
    void moveDocument_contributorWithoutEditorOnTargetFolder_forbidden() {
        fx.folder(F1, SPACE, null, "Réservé").doc(DOC, SPACE, null, "Doc");
        FolderService service = fx.service(5);
        // contributeur : editor sur le document, mais pas sur le dossier cible
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(fx.authorizationService).requireFolderRelation(USER, F1, "editor");

        assertThatThrownBy(() -> service.moveDocument(jwt, DOC, new MoveDocumentRequest(F1, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(fx.jdbc, never()).update(contains("UPDATE documents"), any(), any(), any());
        verify(fx.authorizationService, never()).reparentDocument(any(), any(), any(), any());
        verify(fx.auditService, never()).record(any(), anyBoolean(), eq(AuditActions.DOCUMENT_MOVED),
                anyString(), any(), anyMap(), any());
    }

    @Test
    void moveDocument_withoutEditorOnDocument_forbidden() {
        fx.folder(F1, SPACE, null, "A").doc(DOC, SPACE, null, "Doc");
        FolderService service = fx.service(5);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(fx.authorizationService).requireDocumentRelation(USER, DOC, "editor");

        assertThatThrownBy(() -> service.moveDocument(jwt, DOC, new MoveDocumentRequest(F1, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(fx.jdbc, never()).update(contains("UPDATE documents"), any(), any(), any());
    }

    @Test
    void create_withoutEditorOnParentFolder_forbidden() {
        fx.folder(F1, SPACE, null, "Réservé");
        FolderService service = fx.service(5);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(fx.authorizationService).requireFolderRelation(USER, F1, "editor");

        assertThatThrownBy(() -> service.create(jwt, new CreateFolderRequest(SPACE, F1, "Sous", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(fx.jdbc, never()).update(contains("INSERT INTO folders"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_spaceOwnerBypassesParentFolderEditorCheck() {
        fx.folder(F1, SPACE, null, "Réservé");
        FolderService service = fx.service(5);
        when(fx.authorizationService.hasRelation(USER, "space", SPACE, "owner")).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(fx.authorizationService).requireFolderRelation(USER, F1, "editor");

        service.create(jwt, new CreateFolderRequest(SPACE, F1, "Sous", null));

        verify(fx.jdbc).update(contains("INSERT INTO folders"),
                any(), eq(SPACE), eq(F1), eq("Sous"), any(), eq(USER));
    }

}
