// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public final class SpaceDtos {

    private SpaceDtos() {}

    public record CreateSpaceRequest(@NotBlank String name, String color, String description) {
        public CreateSpaceRequest(String name, String color) {
            this(name, color, null);
        }
    }

    public record UpdateSpaceRequest(
            @NotBlank String name,
            String color,
            /** {@code open} | {@code restricted} — owners seulement ; null = inchangé. */
            String externalReference,
            /** {@code organisation} | {@code space} | {@code restricted} — null = inchangé. */
            String defaultVisibility,
            /** {@code members} | {@code all_readers} — null = inchangé. */
            String commentPolicy,
            /** Texte brut ; {@code null} = inchangé ; vide = effacer. */
            String description
    ) {
        public UpdateSpaceRequest(String name, String color) {
            this(name, color, null, null, null, null);
        }

        public UpdateSpaceRequest(String name, String color, String externalReference) {
            this(name, color, externalReference, null, null, null);
        }

        public UpdateSpaceRequest(String name, String color, String externalReference, String defaultVisibility) {
            this(name, color, externalReference, defaultVisibility, null, null);
        }

        public UpdateSpaceRequest(
                String name, String color, String externalReference, String defaultVisibility, String commentPolicy
        ) {
            this(name, color, externalReference, defaultVisibility, commentPolicy, null);
        }
    }

    /** Aperçu membre : initiales uniquement (pas d'e-mail). */
    public record SpaceMemberPreview(String initials) {}

    public record SpaceView(
            UUID id,
            String name,
            String color,
            String createdAt,
            /** {@code open} (défaut) ou {@code restricted}. */
            String externalReference,
            /** Visibilité par défaut des nouveaux documents. */
            String defaultVisibility,
            /** {@code members} (défaut) ou {@code all_readers}. */
            String commentPolicy,
            boolean canManage,
            boolean isOwner,
            boolean isResponsible,
            /**
             * {@code member} — accès via relations espace ;
             * {@code public-only} — uniquement via documents organisation lisibles.
             */
            String membership,
            /** Texte brut ; null si absent. */
            String description,
            /** Documents visibles par l'appelant (OpenFGA viewer). */
            int documentCount,
            /** Jusqu'à 3 initiales ; le client affiche « +N » via {@code memberExtraCount}. */
            List<SpaceMemberPreview> memberPreview,
            int memberExtraCount
    ) {
        /** Compat sans catalogue (description / compteurs). */
        public SpaceView(
                UUID id,
                String name,
                String color,
                String createdAt,
                String externalReference,
                String defaultVisibility,
                String commentPolicy,
                boolean canManage,
                boolean isOwner,
                boolean isResponsible,
                String membership
        ) {
            this(id, name, color, createdAt, externalReference, defaultVisibility, commentPolicy,
                    canManage, isOwner, isResponsible, membership, null, 0, List.of(), 0);
        }

        /** Compat sans commentPolicy / membership. */
        public SpaceView(
                UUID id,
                String name,
                String color,
                String createdAt,
                String externalReference,
                String defaultVisibility,
                boolean canManage,
                boolean isOwner,
                boolean isResponsible,
                String membership
        ) {
            this(id, name, color, createdAt, externalReference, defaultVisibility, "members",
                    canManage, isOwner, isResponsible, membership, null, 0, List.of(), 0);
        }

        /** Compat sans membership (défaut member). */
        public SpaceView(
                UUID id,
                String name,
                String color,
                String createdAt,
                String externalReference,
                String defaultVisibility,
                boolean canManage,
                boolean isOwner,
                boolean isResponsible
        ) {
            this(id, name, color, createdAt, externalReference, defaultVisibility, "members",
                    canManage, isOwner, isResponsible, "member", null, 0, List.of(), 0);
        }

        /** Compat sans defaultVisibility. */
        public SpaceView(
                UUID id,
                String name,
                String color,
                String createdAt,
                String externalReference,
                boolean canManage,
                boolean isOwner,
                boolean isResponsible
        ) {
            this(id, name, color, createdAt, externalReference, "organisation", "members",
                    canManage, isOwner, isResponsible, "member", null, 0, List.of(), 0);
        }
    }

    public record OwnerView(UUID userId, String email, String displayName, boolean responsible) {}

    public record AddOwnerRequest(@NotNull UUID userId, boolean responsible) {}

    public record SetResponsibleRequest(@NotNull Boolean responsible) {}

    public record GovernanceView(UUID spaceId, List<OwnerView> owners) {}
}
