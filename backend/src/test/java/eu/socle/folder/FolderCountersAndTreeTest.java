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
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static eu.socle.folder.FolderTestSupport.SPACE;
import static eu.socle.folder.FolderTestSupport.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les compteurs et l'arbre sont calculés APRÈS filtrage viewer (aucune fuite
 * du nombre de documents/dossiers restreints).
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
        doNothing().when(fx.authorizationService).requireFolderRelation(any(), any(), any());
        doNothing().when(fx.authorizationService).requireSpaceRelation(any(), any(), any());
    }

    @Test
    void get_documentCountExcludesRestrictedDocuments() {
        // 3 documents dans F1, dont 1 restreint pour ce membre de l'espace
        fx.folder(F1, SPACE, null, "Procédures")
                .doc(D1, SPACE, F1, "Visible 1")
                .doc(D2, SPACE, F1, "Visible 2")
                .doc(D3_RESTRICTED, SPACE, F1, "Restreint");
        when(fx.authorizationService.filterByDocumentViewer(eq(USER), anyCollection()))
                .thenAnswer(inv -> inv.<Collection<UUID>>getArgument(1).stream()
                        .filter(id -> !D3_RESTRICTED.equals(id)).toList());

        FolderView view = service.get(jwt, F1);

        assertThat(view.documentCount()).isEqualTo(2);
        // le filtrage a bien reçu les 3 candidats (la restriction est appliquée côté authz)
        verify(fx.authorizationService).filterByDocumentViewer(
                eq(USER), org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.size() == 3 && ids.contains(D3_RESTRICTED)));
    }

    @Test
    void get_folderCountExcludesRestrictedSubfolders() {
        fx.folder(F1, SPACE, null, "Racine")
                .folder(F2, SPACE, F1, "Ouvert")
                .folder(F3, SPACE, F1, "Restreint");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenAnswer(inv -> inv.<Collection<UUID>>getArgument(1).stream()
                        .filter(id -> !F3.equals(id)).toList());

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
    void tree_documentInInvisibleFolder_isNotLeakedAtRoot() {
        fx.folder(F1, SPACE, null, "Caché")
                .doc(D1, SPACE, F1, "Dans dossier caché")
                .doc(D2, SPACE, null, "À la racine");
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of());
        when(fx.authorizationService.listViewableDocumentIds(USER, DocumentScope.space(SPACE)))
                .thenReturn(List.of(D1, D2));

        SpaceTreeResponse tree = service.tree(jwt, SPACE, null);

        assertThat(tree.folders()).isEmpty();
        assertThat(tree.documents()).extracting(d -> d.id()).containsExactly(D2);
        assertThat(tree.totalDocuments()).isEqualTo(1);
    }

    @Test
    void tree_emptyViewableDocuments_skipsDocumentQuery() {
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
}
