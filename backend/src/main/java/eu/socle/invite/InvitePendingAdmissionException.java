// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

/**
 * Sync JWT : invitation {@code pending} pour cet e-mail vérifié — ne pas créer le compte
 * (l'acceptation le fera). Les autorités restent vides ; le filtre d'admission
 * ne laisse passer que {@code POST /api/v1/invitations/accept}.
 */
public class InvitePendingAdmissionException extends RuntimeException {

    public InvitePendingAdmissionException() {
        super("invitation pending — skip JIT");
    }
}
