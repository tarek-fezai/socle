// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import java.time.Instant;

public final class LicenceDtos {

    private LicenceDtos() {}

    /** GET {@code /api/v1/admin/licence}. */
    public record LicenceView(
            /** {@code valide} | {@code expire_bientot} | {@code expiree} | {@code absente}. */
            String status,
            String licenseId,
            String licensee,
            String edition,
            Instant issuedAt,
            Instant expiresAt,
            Integer maxUsers,
            long activeUsers,
            /** Limite effective (licence ou évaluation). */
            int effectiveMaxUsers,
            boolean evaluationMode,
            String bannerMessage
    ) {}

    /** Corps d'import : JSON licence brut (texte). */
    public record ImportLicenceRequest(String licenceJson) {}
}
