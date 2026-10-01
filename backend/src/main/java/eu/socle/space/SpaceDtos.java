// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public final class SpaceDtos {

    private SpaceDtos() {}

    public record CreateSpaceRequest(@NotBlank String name, String color) {}

    public record UpdateSpaceRequest(
            @NotBlank String name,
            String color,
            /** {@code open} | {@code restricted} — owners seulement ; null = inchangé. */
            String externalReference,
            /** {@code organisation} | {@code space} | {@code restricted} — null = inchangé. */
            String defaultVisibility
    ) {
        public UpdateSpaceRequest(String name, String color) {
            this(name, color, null, null);
        }

        public UpdateSpaceRequest(String name, String color, String externalReference) {
            this(name, color, externalReference, null);
        }
    }

    public record SpaceView(
            UUID id,
            String name,
            String color,
            String createdAt,
            /** {@code open} (défaut) ou {@code restricted}. */
            String externalReference,
            /** Visibilité par défaut des nouveaux documents. */
            String defaultVisibility,
            boolean canManage,
            boolean isOwner,
            boolean isResponsible,
            /**
             * {@code member} — accès via relations espace ;
             * {@code public-only} — uniquement via documents organisation lisibles.
             */
            String membership
    ) {
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
            this(id, name, color, createdAt, externalReference, defaultVisibility,
                    canManage, isOwner, isResponsible, "member");
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
            this(id, name, color, createdAt, externalReference, "organisation",
                    canManage, isOwner, isResponsible, "member");
        }
    }

    public record OwnerView(UUID userId, String email, String displayName, boolean responsible) {}

    public record AddOwnerRequest(@NotNull UUID userId, boolean responsible) {}

    public record SetResponsibleRequest(@NotNull Boolean responsible) {}

    public record GovernanceView(UUID spaceId, List<OwnerView> owners) {}
}
