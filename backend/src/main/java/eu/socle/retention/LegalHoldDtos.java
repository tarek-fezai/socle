// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.retention;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LegalHoldDtos {

    private LegalHoldDtos() {}

    /** {@code scopeType} : {@code document} | {@code space}. */
    public record PlaceLegalHoldRequest(String scopeType, UUID scopeId, String reason) {}

    public record ReleaseLegalHoldRequest(String reason) {}

    public record LegalHoldView(
            UUID id,
            String scopeType,
            UUID scopeId,
            /** Titre du document ou nom de l'espace (null si la ressource n'existe plus). */
            String scopeLabel,
            String reason,
            UUID createdBy,
            String createdByDisplayName,
            Instant createdAt,
            UUID releasedBy,
            String releasedByDisplayName,
            Instant releasedAt,
            String releaseReason,
            boolean active
    ) {}

    public record LegalHoldListResponse(List<LegalHoldView> holds, long activeCount) {}
}
