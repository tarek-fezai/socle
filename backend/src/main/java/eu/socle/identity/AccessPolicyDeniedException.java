// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

/**
 * Refus par la politique d'accès (compte désactivé, groupe manquant, licence…).
 * Traduit en HTTP 403 — JSON classique ou {@code application/problem+json} selon le motif.
 */
public class AccessPolicyDeniedException extends RuntimeException {

    private final AccessDeniedReason reason;
    private final String detail;

    public AccessPolicyDeniedException(AccessDeniedReason reason) {
        this(reason, null);
    }

    public AccessPolicyDeniedException(AccessDeniedReason reason, String detail) {
        super("access_denied: " + reason.code());
        this.reason = reason;
        this.detail = detail;
    }

    public AccessDeniedReason getReason() {
        return reason;
    }

    /** Détail optionnel (ex. message licence) ; peut être {@code null}. */
    public String getDetail() {
        return detail;
    }
}
