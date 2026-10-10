// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Contrat {@code /api/v1/me/tokens} — jamais de hash ; le jeton en clair uniquement à la création. */
public final class PatDtos {

    private PatDtos() {}

    @Schema(name = "PersonalAccessToken")
    public record TokenView(
            UUID id,
            String name,
            @Schema(description = "4 derniers caractères du jeton", minLength = 4, maxLength = 4) String last4,
            PatScope scope,
            Instant createdAt,
            Instant expiresAt,
            @Schema(types = { "string", "null" }, nullable = true, format = "date-time") Instant lastUsedAt,
            PatService.Status status
    ) {}

    @Schema(name = "PersonalAccessTokenCreateRequest")
    public record CreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = PatService.NAME_MAX_LENGTH) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PatScope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    minimum = "" + PatService.MIN_EXPIRY_DAYS, maximum = "" + PatService.MAX_EXPIRY_DAYS)
            Integer expiresInDays
    ) {}

    @Schema(name = "PersonalAccessTokenCreated")
    public record Created(
            TokenView token,
            @Schema(description = "Jeton en clair — renvoyé une seule fois, jamais réaffiché",
                    requiredMode = Schema.RequiredMode.REQUIRED) String plaintext
    ) {
        @Override
        public String toString() {
            return "PatDtos.Created[id=" + token.id() + "]";
        }
    }
}
