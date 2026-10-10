// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

/** Actions d'audit connues (AuditLog.dc.html). */
public final class AuditActions {

    public static final String ACCESS_GRANT_REQUESTED = "access.grant_requested";
    public static final String ACCESS_GRANTED = "access.granted";
    public static final String ACCESS_REVOKE_REQUESTED = "access.revoke_requested";
    public static final String ACCESS_REVOKED = "access.revoked";
    public static final String DOCUMENT_CREATED = "document.created";
    public static final String DOCUMENT_UPDATED = "document.updated";
    public static final String DOCUMENT_VISIBILITY_CHANGED = "document.visibility_changed";
    public static final String DOCUMENT_VERSION_CREATED = "document.version_created";
    public static final String DOCUMENT_VERSION_RESTORED = "document.version_restored";
    public static final String DOCUMENT_SUBMITTED = "document.submitted_for_approval";
    public static final String DOCUMENT_APPROVED = "document.approved";
    public static final String DOCUMENT_REJECTED = "document.rejected";
    /**
     * Approbation annulée : le contenu courant n'est plus celui soumis
     * (mutation pendant la revue). Document remis en {@code brouillon}.
     */
    public static final String DOCUMENT_APPROVAL_INVALIDATED = "document.approval_invalidated";
    public static final String APPROVAL_ESCALATED = "approval.escalated";
    public static final String APPROVAL_CHAIN_EXHAUSTED = "approval.chain_exhausted";
    public static final String DOCUMENT_TRASHED = "document.trashed";
    public static final String FOLDER_TRASHED = "folder.trashed";
    public static final String SPACE_TRASHED = "space.trashed";
    public static final String DOCUMENT_RESTORED_FROM_TRASH = "document.restored_from_trash";
    public static final String FOLDER_RESTORED_FROM_TRASH = "folder.restored_from_trash";
    public static final String SPACE_RESTORED_FROM_TRASH = "space.restored_from_trash";
    public static final String DOCUMENT_PURGED = "document.purged";
    public static final String FOLDER_PURGED = "folder.purged";
    public static final String SPACE_PURGED = "space.purged";

    public static final String FOLDER_CREATED = "folder.created";
    public static final String FOLDER_RENAMED = "folder.renamed";
    public static final String FOLDER_MOVED = "folder.moved";
    /** Soft-delete dossier (corbeille) — alias produit de {@link #FOLDER_TRASHED}. */
    public static final String FOLDER_DELETED = "folder.deleted";
    public static final String FOLDER_RESTORED = "folder.restored";
    public static final String DOCUMENT_MOVED = "document.moved";

    /** Étiquettes et champs personnalisés d'un document (écran d'édition). */
    public static final String DOCUMENT_TAG_ADDED = "document.tag_added";
    public static final String DOCUMENT_TAG_REMOVED = "document.tag_removed";
    public static final String DOCUMENT_CUSTOM_FIELD_UPDATED = "document.custom_field_updated";

    /** Administration des tags (TagsAdmin). */
    public static final String TAG_CREATED = "tag.created";
    public static final String TAG_RENAMED = "tag.renamed";
    public static final String TAG_DELETED = "tag.deleted";
    public static final String TAG_MERGED = "tag.merged";
    public static final String TAG_CREATION_POLICY_CHANGED = "tag.creation_policy_changed";
    /** Transfert d'une attribution de rôle lors d'une fusion de tags gouvernés. */
    public static final String APPROVAL_ROLE_SCOPE_TRANSFERRED = "approval_role.scope_transferred";

    /** Administration des définitions de champs personnalisés (CustomFields). */
    public static final String CUSTOM_FIELD_CREATED = "custom_field.created";
    public static final String CUSTOM_FIELD_UPDATED = "custom_field.updated";
    public static final String CUSTOM_FIELD_ARCHIVED = "custom_field.archived";
    public static final String CUSTOM_FIELD_OPTION_ARCHIVED = "custom_field.option_archived";

    public static final String SPACE_CREATED = "space.created";
    public static final String SPACE_UPDATED = "space.updated";
    public static final String SPACE_OWNER_ADDED = "space.owner_added";
    public static final String SPACE_OWNER_REMOVED = "space.owner_removed";
    public static final String SPACE_RESPONSIBLE_CHANGED = "space.responsible_changed";

    public static final String GROUP_CREATED = "group.created";
    public static final String GROUP_UPDATED = "group.updated";
    public static final String GROUP_DELETED = "group.deleted";
    public static final String GROUP_MEMBER_ADDED = "group.member_added";
    public static final String GROUP_MEMBER_REMOVED = "group.member_removed";

    /** Export documentaire (PDF) — traçabilité a posteriori (fuite potentielle). */
    public static final String DOCUMENT_EXPORTED = "document.exported";
    public static final String FOLDER_EXPORTED = "folder.exported";
    public static final String TAG_EXPORTED = "tag.exported";

    /** Pièces jointes (pas d'audit sur les lectures). */
    public static final String ATTACHMENT_UPLOADED = "attachment.uploaded";
    public static final String ATTACHMENT_PURGED = "attachment.purged";

    /** Définitions d'approbation (admin). */
    public static final String WORKFLOW_CREATED = "workflow.created";
    public static final String WORKFLOW_UPDATED = "workflow.updated";
    public static final String WORKFLOW_DELETED = "workflow.deleted";

    /** Attributions de rôles d'approbation scopées. */
    public static final String APPROVAL_ROLE_ASSIGNED = "approval_role.assigned";
    public static final String APPROVAL_ROLE_UNASSIGNED = "approval_role.unassigned";

    /** Configuration intégrations (intégrateur) — visible au journal pour supervision. */
    public static final String SIEM_CONNECTOR_CREATED = "siem.connector_created";
    public static final String SIEM_CONNECTOR_UPDATED = "siem.connector_updated";
    public static final String SIEM_CONNECTOR_DELETED = "siem.connector_deleted";
    public static final String WEBHOOK_ENDPOINT_CREATED = "webhook.endpoint_created";
    public static final String WEBHOOK_ENDPOINT_UPDATED = "webhook.endpoint_updated";
    public static final String WEBHOOK_ENDPOINT_DELETED = "webhook.endpoint_deleted";

    /** Rôles plateforme internes (admin). */
    public static final String PLATFORM_ROLE_GRANTED = "platform.role_granted";
    public static final String PLATFORM_ROLE_REVOKED = "platform.role_revoked";
    public static final String USER_IDENTITY_LINKED = "user.identity_linked";
    public static final String USER_IDENTITY_UNLINKED = "user.identity_unlinked";
    public static final String USER_IDENTITIES_IMPORTED = "user.identities_imported";

    /** Politique d'accès OIDC (première connexion accordée / refus rate-limité). */
    public static final String AUTH_ACCESS_GRANTED = "auth.access_granted";
    public static final String AUTH_ACCESS_DENIED = "auth.access_denied";
    public static final String USER_DISABLED = "user.disabled";
    public static final String USER_ENABLED = "user.enabled";

    public static final String COMMENT_CREATED = "comment.created";
    public static final String COMMENT_EDITED = "comment.edited";
    public static final String COMMENT_DELETED = "comment.deleted";
    public static final String COMMENT_RESOLVED = "comment.resolved";
    public static final String COMMENT_REOPENED = "comment.reopened";

    public static final String TEMPLATE_CREATED = "template.created";
    public static final String TEMPLATE_UPDATED = "template.updated";
    public static final String TEMPLATE_DELETED = "template.deleted";
    public static final String TEMPLATE_USED = "template.used";
    public static final String TEMPLATE_CREATED_FROM_DOCUMENT = "template.created_from_document";

    /** Campagnes d'attestation de lecture (resource = document, metadata.campaignId). */
    public static final String ATTESTATION_CAMPAIGN_CREATED = "attestation.campaign_created";
    public static final String ATTESTATION_CAMPAIGN_CLOSED = "attestation.campaign_closed";
    public static final String ATTESTATION_ACKNOWLEDGED = "attestation.acknowledged";

    /** Rétention & conformité (admin système). */
    public static final String RETENTION_SETTINGS_UPDATED = "retention.settings_updated";
    public static final String RETENTION_PROCESSING_REGISTER_REVIEWED = "retention.processing_register_reviewed";
    public static final String RETENTION_PURGE_RAN = "retention.purge_ran";
    public static final String LEGAL_HOLD_PLACED = "legal_hold.placed";
    public static final String LEGAL_HOLD_RELEASED = "legal_hold.released";
    /** Réécriture de l'historique Git (purge réelle d'un document). */
    public static final String DOCUMENT_GIT_HISTORY_PURGED = "document.git_history_purged";
    /** Tentative de purge Git (file durable {@code git_purge_queue}). */
    public static final String DOCUMENT_GIT_PURGE_ATTEMPT = "document.git_purge_attempt";

    /** Personnalisation de l'instance (marque blanche, admin système). */
    public static final String BRANDING_UPDATED = "branding.updated";
    public static final String BRANDING_LOGO_UPLOADED = "branding.logo_uploaded";
    public static final String BRANDING_LOGO_REMOVED = "branding.logo_removed";
    public static final String BRANDING_FAVICON_UPLOADED = "branding.favicon_uploaded";
    public static final String BRANDING_FAVICON_REMOVED = "branding.favicon_removed";
    public static final String BRANDING_TEST_EMAIL_SENT = "branding.test_email_sent";

    /** Licence d'instance. */
    public static final String LICENCE_IMPORTED = "licence.imported";
    public static final String LICENCE_REJECTED = "licence.rejected";

    /** Jetons d'accès personnels (metadata : name, last4, scope, expiresAt — jamais le secret). */
    public static final String PAT_CREATED = "pat.created";
    /** metadata.reason : {@code manual} | {@code user_disabled}. */
    public static final String PAT_REVOKED = "pat.revoked";

    public static final String POLL_CLOSED = "poll.closed";
    public static final String LINK_PREVIEW_FETCHED = "link_preview.fetched";

    private AuditActions() {}
}
