// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

/**
 * Refus par la politique d'accès (compte désactivé, groupe manquant, non provisionné…).
 * Traduit en HTTP 403 {@code {"error":"access_denied","reason":"…"}}.
 */
public class AccessPolicyDeniedException extends RuntimeException {

    private final AccessDeniedReason reason;

    public AccessPolicyDeniedException(AccessDeniedReason reason) {
        super("access_denied: " + reason.code());
        this.reason = reason;
    }

    public AccessDeniedReason getReason() {
        return reason;
    }
}
