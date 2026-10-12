// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public final class InvitationDtos {

    private InvitationDtos() {}

    public record CreateRequest(
            @NotBlank @Email String email,
            UUID teamId,
            String teamRole,
            String globalRole,
            Boolean confirmAdmin
    ) {}

    public record AcceptRequest(@NotBlank String token) {}

    public record InvitationView(
            UUID id,
            String email,
            UUID teamId,
            String teamName,
            String teamRole,
            String globalRole,
            String status,
            Instant expiresAt,
            Instant lastSentAt,
            Instant createdAt,
            UUID invitedBy,
            String invitedByName,
            int sendCount
    ) {}

    public record AcceptResponse(UUID userId, String email) {}
}
