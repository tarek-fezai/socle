// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.admin;

import java.time.Instant;

public final class AdminOverviewDtos {

    private AdminOverviewDtos() {}

    /** GET {@code /api/v1/admin/overview} — accueil administration (lecture seule). */
    public record AdminOverviewView(
            OidcSummaryView oidc,
            long userCount,
            long spaceCount,
            PlanSummaryView plan
    ) {}

    public record OidcSummaryView(
            String issuer,
            String clientId,
            /** {@code connected} | {@code unavailable}. */
            String status
    ) {}

    public record PlanSummaryView(
            boolean evaluationMode,
            String edition,
            Instant expiresAt
    ) {}
}
