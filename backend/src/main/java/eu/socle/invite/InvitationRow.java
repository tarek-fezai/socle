// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import java.time.Instant;
import java.util.UUID;

public record InvitationRow(
        UUID id,
        String email,
        UUID teamId,
        String teamRole,
        String globalRole,
        byte[] tokenHash,
        String status,
        Instant expiresAt,
        UUID invitedBy,
        UUID acceptedUserId,
        Instant acceptedAt,
        Instant createdAt,
        Instant lastSentAt,
        int sendCount
) {
    public boolean pending() {
        return "pending".equals(status);
    }

    public boolean expiredClock(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
