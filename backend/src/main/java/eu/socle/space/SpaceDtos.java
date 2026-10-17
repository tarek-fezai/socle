// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import eu.socle.document.DocumentDtos.TagRef;
import eu.socle.folder.FolderDtos.FolderDocumentCard;
import eu.socle.folder.FolderDtos.OwnerGroupRef;
import eu.socle.folder.FolderDtos.StatusCounts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /** Corps TipTap de la page. Le titre reste le nom de l'espace. */
    public record SaveSpacePageRequest(
            @NotNull Map<String, Object> body,
            String changeSummary
    ) {}

    /** Qui a agi en dernier, et quand. */
    public record LastActivity(String who, Instant at) {}

    /** Dossier à la racine de l'espace. Les compteurs excluent la page de dossier. */
    public record SpaceFolderCard(
            UUID id,
            String name,
            int documentCount,
            int inReviewCount,
            BigDecimal reliabilityAverage
    ) {}

    /**
     * {@code GET /spaces/{id}} pour l'écran d'accueil (E2).
     * Les champs de catalogue restent ceux de {@link SpaceView}.
     * Les inventaires excluent la page d'espace.
     */
    public record SpaceDetail(
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
            String membership,
            String description,
            int documentCount,
            List<SpaceMemberPreview> memberPreview,
            int memberExtraCount,
            UUID pageDocumentId,
            boolean canEdit,
            boolean canDelete,
            String language,
            /** {@code actif}. */
            String status,
            OwnerGroupRef owner,
            int memberCount,
            int teamCount,
            /** Quatre premières initiales, ordre du nom affiché. */
            List<SpaceMemberPreview> members,
            LastActivity lastActivity,
            BigDecimal reliabilityAverage,
            StatusCounts statusCounts,
            List<SpaceFolderCard> folders,
            List<FolderDocumentCard> rootDocuments,
            List<FolderDocumentCard> toReview,
            List<TagRef> tags
    ) {}
}
