// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.web;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Codes d'erreur API stables (Problem Detail {@code code}) — l'UI réagit au code, pas au texte.
 */
public final class ApiErrors {

    public static final String APPROVAL_IN_PROGRESS = "approval_in_progress";
    public static final String EDIT_LOCK_HELD = "edit_lock_held";
    public static final String REJECT_JUSTIFICATION_REQUIRED = "reject_justification_required";
    public static final String GOVERNED_TAG_OWNER_ONLY = "governed_tag_owner_only";
    public static final String DIFF_TOO_LARGE = "diff_too_large";
    /** Pièce jointe / multipart au-delà de la limite ({@code MaxUploadSizeExceededException}). */
    public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
    /** Type MIME détecté hors liste blanche (ou SVG). */
    public static final String ATTACHMENT_TYPE_REJECTED = "attachment_type_rejected";
    /** Dimensions image (largeur×hauteur) au-delà de {@code SOCLE_ATTACHMENT_MAX_IMAGE_PIXELS}. */
    public static final String IMAGE_TOO_LARGE = "image_too_large";
    /** Corps TipTap hors liste blanche (type / marque / attribut / URL). */
    public static final String CONTENT_INVALID = "content_invalid";

    public static final String TAG_NAME_CONFLICT = "tag_name_conflict";
    public static final String GOVERNED_TAG_IN_USE = "governed_tag_in_use";
    public static final String TAG_CREATION_RESTRICTED = "tag_creation_restricted";
    public static final String FIELD_TYPE_LOCKED = "field_type_locked";
    public static final String FIELD_OPTION_IN_USE = "field_option_in_use";
    public static final String REQUIRED_FIELD_MISSING = "required_field_missing";
    public static final String LEGAL_HOLD_ACTIVE = "legal_hold_active";
    public static final String LEGAL_HOLD_ALREADY_ACTIVE = "legal_hold_already_active";
    public static final String LEGAL_HOLD_REASON_REQUIRED = "legal_hold_reason_required";
    public static final String ACCENT_CONTRAST_INSUFFICIENT = "accent_contrast_insufficient";
    public static final String BRANDING_IMAGE_TYPE_REJECTED = "branding_image_type_rejected";
    public static final String BRANDING_SENDER_NOT_CONFIGURED = "branding_sender_not_configured";
    public static final String GIT_PURGE_FAILED = "git_purge_failed";
    /** Création d'utilisateur refusée (limite de sièges / licence). */
    public static final String LICENCE_USER_LIMIT = "licence_user_limit";
    public static final String LICENCE_REJECTED = "licence_rejected";

    private ApiErrors() {}

    public static CodedStatusException licenceUserLimit(int maxUsers, long activeUsers, String reason) {
        boolean noValidLicence = maxUsers <= 0
                || "aucune licence".equals(reason)
                || "licence expirée".equals(reason)
                || "licence invalide".equals(reason)
                || (reason != null && reason.startsWith("aucune licence"));
        String detail = noValidLicence
                ? "Aucune licence valide installée : contactez l'administrateur"
                : "Limite d'utilisateurs atteinte (" + activeUsers + "/" + maxUsers
                        + "). Motif : " + reason
                        + ". Les comptes existants restent accessibles.";
        return new CodedStatusException(
                HttpStatus.FORBIDDEN,
                LICENCE_USER_LIMIT,
                detail,
                Map.of("maxUsers", maxUsers, "activeUsers", activeUsers, "reason", reason == null ? "" : reason));
    }

    /** Raccourci : aucune licence valide (évaluation = 0). */
    public static CodedStatusException licenceUserLimitNoLicence() {
        return licenceUserLimit(0, 0, "aucune licence");
    }

    public static CodedStatusException licenceRejected(String detail) {
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                LICENCE_REJECTED,
                detail == null || detail.isBlank() ? "Fichier de licence rejeté" : detail);
    }

    /** {@code detail} doit inclure le chemin JSON du nœud fautif (ex. {@code $.content[0]}). */
    public static CodedStatusException contentInvalid(String path, String reason) {
        String p = path == null || path.isBlank() ? "$" : path;
        String r = reason == null || reason.isBlank() ? "contenu invalide" : reason;
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                CONTENT_INVALID,
                p + ": " + r);
    }

    public static CodedStatusException attachmentTypeRejected(String mediaType) {
        String detail = mediaType == null || mediaType.isBlank()
                ? "Type de fichier non autorisé"
                : "Type de fichier non autorisé : " + mediaType;
        return new CodedStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ATTACHMENT_TYPE_REJECTED, detail);
    }

    public static CodedStatusException payloadTooLarge() {
        return new CodedStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                PAYLOAD_TOO_LARGE,
                "Fichier trop volumineux");
    }

    public static CodedStatusException imageTooLarge(int width, int height, long maxPixels) {
        return new CodedStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                IMAGE_TOO_LARGE,
                "Image trop volumineuse (" + width + "×" + height
                        + ", maximum " + maxPixels + " pixels)");
    }

    public static CodedStatusException approvalInProgress() {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                APPROVAL_IN_PROGRESS,
                "Demande d'approbation en cours");
    }

    public static CodedStatusException editLockHeld(String holderDisplay) {
        String holder = holderDisplay == null || holderDisplay.isBlank()
                ? "un autre utilisateur"
                : holderDisplay;
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                EDIT_LOCK_HELD,
                "Document en cours d'édition par " + holder + " : restauration impossible");
    }

    public static CodedStatusException rejectJustificationRequired() {
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                REJECT_JUSTIFICATION_REQUIRED,
                "Justification obligatoire pour un refus");
    }

    public static CodedStatusException governedTagOwnerOnly() {
        return new CodedStatusException(
                HttpStatus.FORBIDDEN,
                GOVERNED_TAG_OWNER_ONLY,
                "Étiquette gouvernée : seul un owner peut la rattacher ou la détacher");
    }

    public static CodedStatusException diffTooLarge(int versionNo, int lineCount, int maxLines) {
        return new CodedStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                DIFF_TOO_LARGE,
                "Version " + versionNo + " trop volumineuse pour la comparaison ("
                        + lineCount + " lignes, maximum " + maxLines + ")");
    }

    public static CodedStatusException tagNameConflict(String name) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                TAG_NAME_CONFLICT,
                "Un tag nommé « " + name + " » existe déjà");
    }

    public static CodedStatusException governedTagInUse(List<?> assignments) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                GOVERNED_TAG_IN_USE,
                "Tag référencé par des attributions de rôle d'approbation",
                Map.of("assignments", assignments));
    }

    public static CodedStatusException tagCreationRestricted() {
        return new CodedStatusException(
                HttpStatus.FORBIDDEN,
                TAG_CREATION_RESTRICTED,
                "La création de nouveaux tags est réservée aux administrateurs");
    }

    public static CodedStatusException fieldTypeLocked() {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                FIELD_TYPE_LOCKED,
                "Le type ne peut plus être modifié : des valeurs existent déjà");
    }

    public static CodedStatusException fieldOptionInUse(String option, long documentCount) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                FIELD_OPTION_IN_USE,
                "La valeur « " + option + " » est utilisée par " + documentCount + " document(s)",
                Map.of("option", option, "documentCount", documentCount));
    }

    public static CodedStatusException requiredFieldMissing(List<?> fields) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                REQUIRED_FIELD_MISSING,
                "Champs obligatoires manquants pour l'envoi en révision",
                Map.of("fields", fields));
    }

    /** Opération destructive refusée : un legal hold couvre le document ou son espace. */
    public static CodedStatusException legalHoldActive(String scopeType, UUID scopeId) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                LEGAL_HOLD_ACTIVE,
                "Opération refusée : un legal hold est actif (" + scopeType + " " + scopeId + ")",
                Map.of("scopeType", scopeType, "scopeId", scopeId.toString()));
    }

    public static CodedStatusException legalHoldAlreadyActive(String scopeType, UUID scopeId) {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                LEGAL_HOLD_ALREADY_ACTIVE,
                "Un legal hold est déjà actif sur ce périmètre",
                Map.of("scopeType", scopeType, "scopeId", scopeId.toString()));
    }

    public static CodedStatusException legalHoldReasonRequired() {
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                LEGAL_HOLD_REASON_REQUIRED,
                "Motif obligatoire pour poser ou lever un legal hold");
    }

    public static CodedStatusException accentContrastInsufficient(String color, double ratio) {
        return new CodedStatusException(
                HttpStatus.BAD_REQUEST,
                ACCENT_CONTRAST_INSUFFICIENT,
                "Couleur d'accent " + color + " : contraste insuffisant sur fond blanc (WCAG AA ≥ 4.5, obtenu "
                        + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ")",
                Map.of("color", color, "contrastRatio", Math.round(ratio * 100.0) / 100.0, "minimum", 4.5));
    }

    public static CodedStatusException brandingImageTypeRejected(String mediaType) {
        String detail = mediaType == null || mediaType.isBlank()
                ? "Image refusée : PNG ou WebP uniquement (SVG interdit)"
                : "Image refusée (" + mediaType + ") : PNG ou WebP uniquement (SVG interdit)";
        return new CodedStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, BRANDING_IMAGE_TYPE_REJECTED, detail);
    }

    public static CodedStatusException brandingSenderNotConfigured() {
        return new CodedStatusException(
                HttpStatus.CONFLICT,
                BRANDING_SENDER_NOT_CONFIGURED,
                "Adresse d'expédition non configurée");
    }

    public static CodedStatusException gitPurgeFailed(UUID documentId) {
        return new CodedStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                GIT_PURGE_FAILED,
                "Purge de l'historique Git impossible pour le document " + documentId);
    }
}
