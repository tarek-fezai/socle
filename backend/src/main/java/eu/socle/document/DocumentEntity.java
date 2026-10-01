// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    private UUID id;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(nullable = false)
    private int position = 0;

    @Column(nullable = false)
    private String title;

    /** Type libre — apparié à {@code approval_workflows.scope_doc_type}. */
    @Column(name = "doc_type")
    private String docType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> body;

    @Column(nullable = false)
    private String status;

    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo = 1;

    @Column(name = "reliability_score", precision = 5, scale = 2)
    private BigDecimal reliabilityScore;

    @Column(name = "reliability_computed_at")
    private Instant reliabilityComputedAt;

    @Column(name = "is_mandatory_ack", nullable = false)
    private boolean mandatoryAck;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** HEAD commit Git (mode git) — verrou optimiste, pas la source de lecture du corps. */
    @Column(name = "git_head_sha")
    private String gitHeadSha;

    /** organisation | space | restricted — défaut space (migration) ; création = default_visibility espace. */
    @Column(nullable = false)
    private String visibility = DocumentVisibility.SPACE;

    @Column(name = "created_by")
    private UUID createdBy;

    /** Auteur du contenu courant — mis à jour à create/update/restore, pas à la soumission. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Modèle d'origine (snapshot de provenance — pas de synchronisation ultérieure). */
    @Column(name = "template_id")
    private UUID templateId;

    /** Version du modèle au moment de la création du document. */
    @Column(name = "template_version")
    private Integer templateVersion;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = "brouillon";
        }
        if (visibility == null || visibility.isBlank()) {
            visibility = DocumentVisibility.SPACE;
        }
        if (currentVersionNo <= 0) {
            currentVersionNo = 1;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(UUID spaceId) {
        this.spaceId = spaceId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Map<String, Object> getBody() {
        return body;
    }

    public void setBody(Map<String, Object> body) {
        this.body = body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCurrentVersionNo() {
        return currentVersionNo;
    }

    public void setCurrentVersionNo(int currentVersionNo) {
        this.currentVersionNo = currentVersionNo;
    }

    public BigDecimal getReliabilityScore() {
        return reliabilityScore;
    }

    public void setReliabilityScore(BigDecimal reliabilityScore) {
        this.reliabilityScore = reliabilityScore;
    }

    public Instant getReliabilityComputedAt() {
        return reliabilityComputedAt;
    }

    public void setReliabilityComputedAt(Instant reliabilityComputedAt) {
        this.reliabilityComputedAt = reliabilityComputedAt;
    }

    public boolean isMandatoryAck() {
        return mandatoryAck;
    }

    public void setMandatoryAck(boolean mandatoryAck) {
        this.mandatoryAck = mandatoryAck;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(UUID deletedBy) {
        this.deletedBy = deletedBy;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getGitHeadSha() {
        return gitHeadSha;
    }

    public void setGitHeadSha(String gitHeadSha) {
        this.gitHeadSha = gitHeadSha;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(Integer templateVersion) {
        this.templateVersion = templateVersion;
    }
}
