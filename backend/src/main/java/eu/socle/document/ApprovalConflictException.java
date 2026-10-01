// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conflit sur decide — codes distincts pour le frontend ({@code already_resolved} vs {@code step_advanced}).
 */
public class ApprovalConflictException extends ResponseStatusException {

    public static final String ALREADY_RESOLVED = "already_resolved";
    public static final String STEP_ADVANCED = "step_advanced";

    private final String error;

    public ApprovalConflictException(String error, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.error = error;
    }

    public String getError() {
        return error;
    }

    public static ApprovalConflictException alreadyResolved() {
        return new ApprovalConflictException(
                ALREADY_RESOLVED,
                "Demande déjà résolue");
    }

    public static ApprovalConflictException stepAdvanced(int expected, int actual) {
        return new ApprovalConflictException(
                STEP_ADVANCED,
                "Étape avancée (attendu " + expected + ", actuel " + actual + ")");
    }
}
