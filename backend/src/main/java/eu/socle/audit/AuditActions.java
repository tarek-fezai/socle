// SPDX-License-Identifier: AGPL-3.0-or-later
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

    private AuditActions() {}
}
