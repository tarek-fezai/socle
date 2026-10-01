// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.authz.DocumentScope;
import eu.socle.folder.FolderDtos.FolderView;
import eu.socle.folder.FolderDtos.SpaceTreeResponse;
import eu.socle.folder.FolderDtos.TreeFolderNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static eu.socle.folder.FolderTestSupport.SPACE;
import static eu.socle.folder.FolderTestSupport.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compteurs et arbre calculés APRÈS filtrage : viewer dossier ∪ ancêtres des docs
 * lisibles. Non-membre sans doc lisible → 404. Compteurs sans N+1 OpenFGA.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderCountersAndTreeTest {

    static final UUID F1 = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    static final UUID F2 = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    static final UUID F3 = UUID.fromString("f0000000-0000-0000-0000-000000000003");
    static final UUID D1 = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    static final UUID D2 = UUID.fromString("d0000000-0000-0000-0000-000000000002");
    static final UUID D3_RESTRICTED = UUID.fromString("d0000000-0000-0000-0000-000000000003");

    FolderTestSupport fx;
    FolderService service;
    Jwt jwt;

    @BeforeEach
    void setUp() {
        fx = new FolderTestSupport();
        service = fx.service(5);
        jwt = FolderTestSupport.jwt();
        // Membre par défaut
        when(fx.authorizationService.hasRelation(eq(USER), eq("space"), eq(SPACE), eq("viewer")))
                .thenReturn(true);
    }

    @Test
    void get_documentCountExcludesRestrictedDocuments() {
        fx.folder(F1, SPACE, null, "Procédures")
                .doc(D1, SPACE, F1, "Visible 1")
                .doc(D2, SPACE, F1, "Visible 2")
                .doc(D3_RESTRICTED, SPACE, F1, "Restreint");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of(F1));
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of(D1, D2));

        FolderView view = service.get(jwt, F1);

        assertThat(view.documentCount()).isEqualTo(2);
        verify(fx.authorizationService).listViewableDocumentIds(USER, DocumentScope.space(SPACE));
        verify(fx.authorizationService, never()).filterByDocumentViewer(any(), anyCollection());
    }

    @Test
    void get_folderCountExcludesRestrictedSubfolders() {
        fx.folder(F1, SPACE, null, "Racine")
                .folder(F2, SPACE, F1, "Ouvert")
                .folder(F3, SPACE, F1, "Restreint");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenAnswer(inv -> inv.<java.util.Collection<UUID>>getArgument(1).stream()
                        .filter(id -> !F3.equals(id)).toList());
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of());

        FolderView view = service.get(jwt, F1);

        assertThat(view.folderCount()).isEqualTo(1);
    }

    @Test
    void tree_omitsRestrictedDocumentsAndFolders_andCountsAfterFilter() {
        fx.folder(F1, SPACE, null, "Racine")
                .folder(F2, SPACE, F1, "Restreint")
                .doc(D1, SPACE, F1, "Visible 1")
                .doc(D2, SPACE, F1, "Visible 2")
                .doc(D3_RESTRICTED, SPACE, F1, "Restreint");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of(F1));
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of(D1, D2));

        SpaceTreeResponse tree = service.tree(jwt, SPACE, null);

        assertThat(tree.totalFolders()).isEqualTo(1);
        assertThat(tree.totalDocuments()).isEqualTo(2);
        assertThat(tree.folders()).hasSize(1);
        TreeFolderNode root = tree.folders().getFirst();
        assertThat(root.id()).isEqualTo(F1);
        assertThat(root.documentCount()).isEqualTo(2);
        assertThat(root.folderCount()).isZero();
        assertThat(root.folders()).isEmpty();
        assertThat(root.documents()).extracting(d -> d.id()).containsExactlyInAnyOrder(D1, D2);
        assertThat(root.documents()).extracting(d -> d.title()).doesNotContain("Restreint");
    }

    @Test
    void tree_nonMember_seesAncestorPathOfOrganisationDoc_notSiblingFolders() {
        // Racine → Publique (doc org) ; Frère caché (doc space)
        fx.folder(F1, SPACE, null, "Racine")
                .folder(F2, SPACE, F1, "Publique")
                .folder(F3, SPACE, F1, "Frère")
                .doc(D1, SPACE, F2, "Page org")
                .doc(D2, SPACE, F3, "Page space");
        when(fx.authorizationService.hasRelation(eq(USER), eq("space"), eq(SPACE), eq("viewer")))
                .thenReturn(false);
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of());
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of(D1));

        SpaceTreeResponse tree = service.tree(jwt, SPACE, null);

        assertThat(tree.totalFolders()).isEqualTo(2); // Racine + Publique
        assertThat(tree.totalDocuments()).isEqualTo(1);
        assertThat(tree.folders()).hasSize(1);
        TreeFolderNode root = tree.folders().getFirst();
        assertThat(root.id()).isEqualTo(F1);
        assertThat(root.folders()).extracting(TreeFolderNode::id).containsExactly(F2);
        assertThat(root.folders()).extracting(TreeFolderNode::name).doesNotContain("Frère");
        assertThat(root.folders().getFirst().documents()).extracting(d -> d.id()).containsExactly(D1);
    }

    @Test
    void tree_nonMember_withoutReadableDocs_returns404() {
        fx.folder(F1, SPACE, null, "Racine");
        when(fx.authorizationService.hasRelation(eq(USER), eq("space"), eq(SPACE), eq("viewer")))
                .thenReturn(false);
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.tree(jwt, SPACE, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(fx.authorizationService, never()).filterByFolderViewer(any(), anyCollection());
    }

    @Test
    void tree_documentInFolder_showsFolderAsAncestorEvenWithoutFolderViewer() {
        fx.folder(F1, SPACE, null, "Chemin")
                .doc(D1, SPACE, F1, "Dans dossier")
                .doc(D2, SPACE, null, "À la racine");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of());
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of(D1, D2));

        SpaceTreeResponse tree = service.tree(jwt, SPACE, null);

        assertThat(tree.folders()).extracting(TreeFolderNode::id).containsExactly(F1);
        assertThat(tree.folders().getFirst().documents()).extracting(d -> d.id()).containsExactly(D1);
        assertThat(tree.documents()).extracting(d -> d.id()).containsExactly(D2);
        assertThat(tree.totalDocuments()).isEqualTo(2);
    }

    @Test
    void tree_emptyViewableDocuments_skipsDocumentQuery_whenMember() {
        fx.folder(F1, SPACE, null, "Racine");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of(F1));
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of());

        SpaceTreeResponse tree = service.tree(jwt, SPACE, null);

        assertThat(tree.totalDocuments()).isZero();
        verify(fx.jdbc, never()).query(org.mockito.ArgumentMatchers.contains("lower(title)"),
                any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }

    @Test
    void tree_filterByFolderViewerCalledOnce_notPerFolder() {
        for (int i = 0; i < 50; i++) {
            fx.folder(new UUID(0xF000000000000000L, i), SPACE, null, "F" + i);
        }
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenAnswer(inv -> List.copyOf(inv.getArgument(1)));
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of());

        service.tree(jwt, SPACE, null);

        verify(fx.authorizationService, times(1)).filterByFolderViewer(eq(USER), anyCollection());
        verify(fx.authorizationService, times(1)).listViewableDocumentIds(USER, DocumentScope.space(SPACE));
        verify(fx.authorizationService, never()).filterByDocumentViewer(any(), anyCollection());
    }
}
