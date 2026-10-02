// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

/** Motifs de refus de la politique d'accès (valeur publique = {@link #code()}). */
public enum AccessDeniedReason {

    NOT_IN_ALLOWED_GROUP("not_in_allowed_group"),
    ACCOUNT_DISABLED("account_disabled"),
    NOT_PROVISIONED("not_provisioned"),
    EMAIL_DOMAIN_NOT_ALLOWED("email_domain_not_allowed");

    private final String code;

    AccessDeniedReason(String code) {
        this.code = code;
    }

    /** Code renvoyé au client (corps 403) et écrit dans l'audit. */
    public String code() {
        return code;
    }
}
