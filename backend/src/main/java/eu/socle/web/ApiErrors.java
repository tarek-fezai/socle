// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import org.springframework.http.HttpStatus;

/**
 * Codes d'erreur API stables (Problem Detail {@code code}) — l'UI réagit au code, pas au texte.
 */
public final class ApiErrors {

    public static final String APPROVAL_IN_PROGRESS = "approval_in_progress";
    public static final String EDIT_LOCK_HELD = "edit_lock_held";
    public static final String REJECT_JUSTIFICATION_REQUIRED = "reject_justification_required";
    public static final String GOVERNED_TAG_OWNER_ONLY = "governed_tag_owner_only";
    public static final String DIFF_TOO_LARGE = "diff_too_large";

    private ApiErrors() {}

    public static CodedStatusException approvalInProgress() {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                APPROVAL_IN_PROGRESS,
                "Demande d'approbation en cours");
    }

    public static CodedStatusException editLockHeld(String holderDisplay) {
        String holder = holderDisplay == null || holderDisplay.isBlank()
                ? "un autre utilisateur"
                : holderDisplay;
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                EDIT_LOCK_HELD,
                "Document en cours d'édition par " + holder + " : restauration impossible");
    }

    public static CodedStatusException rejectJustificationRequired() {
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                REJECT_JUSTIFICATION_REQUIRED,
                "Justification obligatoire pour un refus");
    }

    public static CodedStatusException governedTagOwnerOnly() {
        return new CodedStatusException(
                HttpStatus.FORBIDDEN,
                GOVERNED_TAG_OWNER_ONLY,
                "Étiquette gouvernée : seul un owner peut la rattacher ou la détacher");
    }

    public static CodedStatusException diffTooLarge(int versionNo, int lineCount, int maxLines) {
        return new CodedStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                DIFF_TOO_LARGE,
                "Version " + versionNo + " trop volumineuse pour la comparaison ("
                        + lineCount + " lignes, maximum " + maxLines + ")");
    }
}
