// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {}

    public record DocumentSummary(
            UUID id,
            String title,
            String status,
            Instant updatedAt,
            BigDecimal reliabilityScore,
            boolean stale,
            Instant contentModifiedAt
    ) {}

    /**
     * @param total estimation (présélection SQL avant Check) — voir {@code totalIsEstimate}
     * @param totalIsEstimate toujours {@code true}
     * @param warning non null si refill authz tronqué
     */
    public record DocumentListPage(
            List<DocumentSummary> results,
            int total,
            boolean totalIsEstimate,
            String warning
    ) {}

    /**
     * @param governed vrai si l'étiquette est référencée par une attribution de rôle d'approbation
     *                 ({@code approval_role_assignments.scope_type = 'tag'}) : seuls les owners
     *                 peuvent la rattacher / détacher.
     */
    public record TagRef(UUID id, String name, String color, boolean governed) {}

    /**
     * Personne résolue pour l'affichage (auteur, propriétaire).
     * Un champ {@code PersonRef} {@code null} dans une réponse signifie « inconnu / hérité /
     * migration » (jamais une fausse personne) ; le frontend affiche « Système (migration) ».
     */
    public record PersonRef(UUID id, String displayName, String initials) {
        public static final String DELETED_LABEL = "Utilisateur supprimé";

        /** Compte supprimé / anonymisé : libellé neutre, l'identifiant technique est conservé. */
        public static PersonRef deleted(UUID id) {
            return new PersonRef(id, DELETED_LABEL, initialsOf(DELETED_LABEL));
        }

        /** Initiales : 1 mot → 2 premières lettres ; sinon première lettre du premier + dernier mot. */
        public static String initialsOf(String name) {
            if (name == null || name.isBlank()) {
                return "?";
            }
            String[] parts = name.trim().split("\\s+");
            if (parts.length == 1) {
                return parts[0].substring(0, Math.min(2, parts[0].length()))
                        .toUpperCase(java.util.Locale.ROOT);
            }
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1))
                    .toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Droits de l'appelant sur le document (calculés via un seul BatchCheck OpenFGA).
     * Indications d'affichage : le serveur re-vérifie chaque action.
     */
    public record DocumentPermissions(
            boolean canEdit,
            boolean canPublish,
            boolean canManageAccess,
            boolean canComment,
            boolean canManageAttestations
    ) {
        public static final DocumentPermissions NONE =
                new DocumentPermissions(false, false, false, false, false);
    }

    public record DocumentResponse(
            UUID id,
            UUID spaceId,
            UUID folderId,
            String title,
            String docType,
            Map<String, Object> body,
            String status,
            int currentVersionNo,
            Instant createdAt,
            Instant updatedAt,
            BigDecimal reliabilityScore,
            Instant reliabilityComputedAt,
            boolean stale,
            Instant contentModifiedAt,
            int stalenessThresholdDays,
            /** organisation | space | restricted */
            String visibility,
            int position,
            /** Modèle d'origine (null si page vierge ou modèle supprimé depuis). */
            UUID templateId,
            /** Version du modèle à la création. */
            Integer templateVersion,
            /** Créateur du document (null si inconnu / données héritées / migration). */
            PersonRef createdBy,
            /** Auteur du contenu courant (null si inconnu / données héritées / migration). */
            PersonRef updatedBy,
            /** Propriétaire résolu de l'espace (responsable en priorité, sinon premier owner). */
            PersonRef owner,
            /** Étiquettes du document, triées par nom. */
            List<TagRef> tags,
            /** Droits de l'appelant sur ce document. */
            DocumentPermissions permissions
    ) {
        public DocumentResponse {
            tags = tags == null ? List.of() : List.copyOf(tags);
            permissions = permissions == null ? DocumentPermissions.NONE : permissions;
        }

        /** Compat appels sans auteurs ni étiquettes. */
        public DocumentResponse(
                UUID id,
                UUID spaceId,
                UUID folderId,
                String title,
                String docType,
                Map<String, Object> body,
                String status,
                int currentVersionNo,
                Instant createdAt,
                Instant updatedAt,
                BigDecimal reliabilityScore,
                Instant reliabilityComputedAt,
                boolean stale,
                Instant contentModifiedAt,
                int stalenessThresholdDays,
                String visibility,
                int position,
                UUID templateId,
                Integer templateVersion
        ) {
            this(
                    id, spaceId, folderId, title, docType, body, status, currentVersionNo,
                    createdAt, updatedAt, reliabilityScore, reliabilityComputedAt,
                    stale, contentModifiedAt, stalenessThresholdDays, visibility, position,
                    templateId, templateVersion, null, null, null, List.of(), DocumentPermissions.NONE);
        }

        /** Compat appels sans provenance modèle. */
        public DocumentResponse(
                UUID id,
                UUID spaceId,
                UUID folderId,
                String title,
                String docType,
                Map<String, Object> body,
                String status,
                int currentVersionNo,
                Instant createdAt,
                Instant updatedAt,
                BigDecimal reliabilityScore,
                Instant reliabilityComputedAt,
                boolean stale,
                Instant contentModifiedAt,
                int stalenessThresholdDays,
                String visibility,
                int position
        ) {
            this(
                    id, spaceId, folderId, title, docType, body, status, currentVersionNo,
                    createdAt, updatedAt, reliabilityScore, reliabilityComputedAt,
                    stale, contentModifiedAt, stalenessThresholdDays, visibility, position,
                    null, null);
        }

        /** Compat tests / appels sans folderId / position / visibility. */
        public DocumentResponse(
                UUID id,
                UUID spaceId,
                String title,
                String docType,
                Map<String, Object> body,
                String status,
                int currentVersionNo,
                Instant createdAt,
                Instant updatedAt,
                BigDecimal reliabilityScore,
                Instant reliabilityComputedAt,
                boolean stale,
                Instant contentModifiedAt,
                int stalenessThresholdDays
        ) {
            this(
                    id, spaceId, null, title, docType, body, status, currentVersionNo,
                    createdAt, updatedAt, reliabilityScore, reliabilityComputedAt,
                    stale, contentModifiedAt, stalenessThresholdDays, DocumentVisibility.SPACE, 0);
        }

        public DocumentResponse(
                UUID id,
                UUID spaceId,
                String title,
                String docType,
                Map<String, Object> body,
                String status,
                int currentVersionNo,
                Instant createdAt,
                Instant updatedAt,
                BigDecimal reliabilityScore,
                Instant reliabilityComputedAt,
                boolean stale,
                Instant contentModifiedAt,
                int stalenessThresholdDays,
                String visibility
        ) {
            this(
                    id, spaceId, null, title, docType, body, status, currentVersionNo,
                    createdAt, updatedAt, reliabilityScore, reliabilityComputedAt,
                    stale, contentModifiedAt, stalenessThresholdDays, visibility, 0);
        }
    }

    /**
     * @param body obligatoire sauf si {@code templateId} est fourni (validé dans
     *             {@link DocumentService#create}) ; avec modèle, le corps vient du modèle.
     * @param templateId modèle de page d'origine (variables {@code {{date}}}… substituées côté serveur)
     */
    public record CreateDocumentRequest(
            @NotBlank String title,
            Map<String, Object> body,
            String docType,
            @NotNull UUID spaceId,
            /** Surcharge du default_visibility de l'espace — réservé aux owners. */
            String visibility,
            UUID folderId,
            UUID templateId
    ) {
        public CreateDocumentRequest(String title, Map<String, Object> body) {
            this(title, body, null, null, null, null, null);
        }

        public CreateDocumentRequest(String title, Map<String, Object> body, String docType) {
            this(title, body, docType, null, null, null, null);
        }

        public CreateDocumentRequest(String title, Map<String, Object> body, String docType, UUID spaceId) {
            this(title, body, docType, spaceId, null, null, null);
        }

        public CreateDocumentRequest(String title, Map<String, Object> body, String docType, UUID spaceId, String visibility) {
            this(title, body, docType, spaceId, visibility, null, null);
        }

        public CreateDocumentRequest(
                String title,
                Map<String, Object> body,
                String docType,
                UUID spaceId,
                String visibility,
                UUID folderId
        ) {
            this(title, body, docType, spaceId, visibility, folderId, null);
        }
    }

    public record UpdateVisibilityRequest(@NotBlank String visibility) {}

    public record UpdateDocumentRequest(
            @NotBlank String title,
            @NotNull Map<String, Object> body,
            String changeSummary,
            String docType,
            /** Version courante au chargement — mismatch → 409 (mode relational + cohérence client). */
            Integer expectedVersionNo
    ) {
        public UpdateDocumentRequest(String title, Map<String, Object> body) {
            this(title, body, null, null, null);
        }

        public UpdateDocumentRequest(String title, Map<String, Object> body, String changeSummary) {
            this(title, body, changeSummary, null, null);
        }

        public UpdateDocumentRequest(String title, Map<String, Object> body, String changeSummary, int expectedVersionNo) {
            this(title, body, changeSummary, null, expectedVersionNo);
        }
    }

    public record VersionSummary(
            int versionNo,
            UUID authorId,
            UUID archivedBy,
            String changeSummary,
            Instant createdAt
    ) {}

    public record VersionPage(
            List<VersionSummary> items,
            int offset,
            int limit,
            long total
    ) {}

    public record VersionDetail(
            UUID documentId,
            int versionNo,
            Map<String, Object> bodySnapshot,
            UUID authorId,
            UUID archivedBy,
            String changeSummary,
            Instant createdAt
    ) {}

    public record VersionDiffResponse(
            UUID documentId,
            int fromVersion,
            int toVersion,
            List<DiffChange> changes
    ) {}

    public record DiffChange(
            String path,
            String op,
            Object before,
            Object after
    ) {}
}
