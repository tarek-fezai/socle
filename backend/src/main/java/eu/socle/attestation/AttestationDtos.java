// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attestation;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AttestationDtos {

    private AttestationDtos() {}

    /**
     * Corps de création — accepte {@code audience_type} (snake_case, contrat API)
     * et {@code audienceType} (camelCase).
     *
     * @param audienceType {@code space_members} | {@code group}
     * @param audienceRef  obligatoire pour {@code group} (id du groupe), ignoré pour {@code space_members}
     */
    public record CreateCampaignRequest(
            @NotBlank @JsonAlias("audience_type") String audienceType,
            @JsonAlias("audience_ref") UUID audienceRef,
            @JsonAlias("due_date") LocalDate dueDate
    ) {}

    /** Campagne telle que vue par un owner d'espace (création). */
    public record CampaignView(
            UUID id,
            UUID documentId,
            int versionNo,
            String audienceType,
            UUID audienceRef,
            int audienceSize,
            LocalDate dueDate,
            UUID createdBy,
            Instant createdAt,
            Instant closedAt
    ) {}

    /**
     * Campagne ouverte vue par un utilisateur concerné.
     *
     * @param ackCount     X — accusés reçus
     * @param audienceSize Y — taille d'audience figée à la création
     * @param versionNo    version du document à la création de la campagne
     * @param currentVersionNo version courante du document
     * @param acknowledgedVersionNo version accusée par l'utilisateur (null si pas d'accusé)
     */
    public record ActiveAttestation(
            UUID campaignId,
            UUID documentId,
            int versionNo,
            int currentVersionNo,
            String audienceType,
            UUID audienceRef,
            LocalDate dueDate,
            boolean overdue,
            boolean acknowledged,
            Instant acknowledgedAt,
            Integer acknowledgedVersionNo,
            int ackCount,
            int audienceSize,
            Instant createdAt
    ) {}

    /** Ligne nominative — réservée aux owners de l'espace. */
    public record AcknowledgmentView(
            UUID userId,
            String displayName,
            String email,
            Instant acknowledgedAt,
            int versionNo
    ) {}
}
