// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.folder.FolderDtos.MoveDocumentRequest;
import eu.socle.folder.FolderDtos.MoveFolderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static eu.socle.folder.FolderTestSupport.SPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * Un déplacement dans le même espace ne modifie que {@code folder_id}/{@code position} :
 * la table {@code document_links} (liens et transclusions) n'est jamais touchée.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderMoveLinksUnchangedTest {

    static final UUID F1 = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    static final UUID F2 = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    static final UUID DOC = UUID.fromString("d0000000-0000-0000-0000-000000000001");

    FolderTestSupport fx;

    @BeforeEach
    void setUp() {
        fx = new FolderTestSupport();
        doNothing().when(fx.authorizationService).requireDocumentRelation(any(), any(), any());
        doNothing().when(fx.authorizationService).requireFolderRelation(any(), any(), any());
        doNothing().when(fx.authorizationService).requireSpaceRelation(any(), any(), any());
    }

    @Test
    void moveDocument_sameSpace_neverTouchesDocumentLinks() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, null, "B").doc(DOC, SPACE, F1, "Doc");

        fx.service(5).moveDocument(FolderTestSupport.jwt(), DOC, new MoveDocumentRequest(F2, null));

        List<String> sql = FolderTestSupport.sqlSeen(fx.jdbc);
        assertThat(sql).noneMatch(s -> s.contains("document_links"));
        assertThat(sql).noneMatch(s -> s.toUpperCase().contains("DELETE FROM"));
        // seule mutation : UPDATE documents SET folder_id, position
        assertThat(sql).filteredOn(s -> s.trim().toUpperCase().startsWith("UPDATE")
                || s.trim().toUpperCase().startsWith("INSERT"))
                .hasSize(1)
                .allMatch(s -> s.contains("UPDATE documents") && s.contains("folder_id"));
    }

    @Test
    void moveDocument_toSpaceRoot_neverTouchesDocumentLinks() {
        fx.folder(F1, SPACE, null, "A").doc(DOC, SPACE, F1, "Doc");

        fx.service(5).moveDocument(FolderTestSupport.jwt(), DOC, new MoveDocumentRequest(null, null));

        assertThat(FolderTestSupport.sqlSeen(fx.jdbc)).noneMatch(s -> s.contains("document_links"));
    }

    @Test
    void moveFolder_neverTouchesDocumentLinks() {
        fx.folder(F1, SPACE, null, "A").folder(F2, SPACE, null, "B").doc(DOC, SPACE, F1, "Doc");

        fx.service(5).move(FolderTestSupport.jwt(), F1, new MoveFolderRequest(F2, null));

        assertThat(FolderTestSupport.sqlSeen(fx.jdbc)).noneMatch(s -> s.contains("document_links"));
    }
}
