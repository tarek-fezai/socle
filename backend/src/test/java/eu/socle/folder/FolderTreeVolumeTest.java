// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.authz.DocumentScope;
import eu.socle.folder.FolderDtos.SpaceTreeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Garde-fou « volume » de l'arbre d'espace : pour 500 documents, {@link FolderService#tree}
 * doit passer par UNE résolution bornée {@link DocumentScope#space(UUID)}
 * ({@code listViewableDocumentIds}) et jamais par un check OpenFGA par document
 * ni par une résolution globale.
 *
 * <p>Le test de volume complet (insertion réelle de 500 lignes + OpenFGA réel) reste
 * optionnel et relève de la CI ; ici on vérifie uniquement la forme des appels.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderTreeVolumeTest {

    static final int DOCS = 500;
    static final UUID ROOT = UUID.fromString("f0000000-0000-0000-0000-000000000001");

    FolderTestSupport fx;

    @BeforeEach
    void setUp() {
        fx = new FolderTestSupport();
        doNothing().when(fx.authorizationService).requireSpaceRelation(any(), any(), any());
    }

    @Test
    void tree_with500Documents_usesSpaceScopeOnce_andNoPerDocumentChecks() {
        fx.folder(ROOT, SPACE, null, "Racine");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            UUID id = new UUID(0xD0C0000000000000L, i);
            ids.add(id);
            fx.doc(id, SPACE, i % 2 == 0 ? ROOT : null, "Doc " + i);
        }
        when(fx.authorizationService.filterByFolderViewer(eq(USER), anyCollection()))
                .thenReturn(List.of(ROOT));
        when(fx.authorizationService.listViewableDocumentIds(any(), any(DocumentScope.class)))
                .thenReturn(ids);

        SpaceTreeResponse tree = fx.service(5).tree(FolderTestSupport.jwt(), SPACE, null);

        assertThat(tree.totalDocuments()).isEqualTo(DOCS);
        assertThat(tree.folders().getFirst().documentCount()).isEqualTo(DOCS / 2);
        assertThat(tree.documents()).hasSize(DOCS / 2);

        // une seule résolution, bornée à l'espace
        verify(fx.authorizationService, times(1)).listViewableDocumentIds(USER, DocumentScope.space(SPACE));
        verify(fx.authorizationService, never()).listViewableDocumentIds(any(), eq(DocumentScope.global()));
        // aucun check par document (N+1)
        verify(fx.authorizationService, never()).filterByDocumentViewer(any(), anyCollection());
        verify(fx.authorizationService, never()).hasRelation(any(), eq("document"), any(), any());
    }
}
