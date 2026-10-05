// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.privacy;

import eu.socle.comment.CommentService;
import eu.socle.document.DocumentDraftService;
import eu.socle.retention.LegalHoldService;
import eu.socle.user.UserSyncService;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RGPD : anonymisation / effacement refusés (409 legal_hold_active) tant qu'un gel couvre les données. */
class PersonalDataEraseLegalHoldTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    CommentService comments = mock(CommentService.class);
    DocumentDraftService drafts = mock(DocumentDraftService.class);
    LegalHoldService holds = mock(LegalHoldService.class);
    PersonalDataExportService service =
            new PersonalDataExportService(comments, drafts, mock(UserSyncService.class));

    @Test
    void erase_underLegalHold_isRefused_andNothingIsModified() {
        doThrow(ApiErrors.legalHoldActive("document", UUID.randomUUID()))
                .when(holds).assertUserDataNotHeld(USER);
        service.setLegalHoldService(holds);

        assertThatThrownBy(() -> service.anonymizeUserComments(USER))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.LEGAL_HOLD_ACTIVE);
        assertThatThrownBy(() -> service.erasePersonalDrafts(USER))
                .isInstanceOf(CodedStatusException.class);

        verify(comments, never()).anonymizeAuthor(USER);
        verify(drafts, never()).deleteAllForUser(USER);
    }

    @Test
    void erase_withoutHold_proceeds() {
        when(comments.anonymizeAuthor(USER)).thenReturn(3);
        when(drafts.deleteAllForUser(USER)).thenReturn(2);
        service.setLegalHoldService(holds);

        assertThat(service.anonymizeUserComments(USER)).isEqualTo(3);
        assertThat(service.erasePersonalDrafts(USER)).isEqualTo(2);
    }
}
