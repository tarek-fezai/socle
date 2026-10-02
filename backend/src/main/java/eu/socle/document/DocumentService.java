// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.activity.ActivityEventService;
import eu.socle.activity.ActivityEventTypes;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.DiffChange;
import eu.socle.document.DocumentDtos.DocumentListPage;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.DocumentSummary;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentDtos.VersionDetail;
import eu.socle.document.DocumentDtos.VersionDiffResponse;
import eu.socle.document.DocumentDtos.VersionPage;
import eu.socle.document.DocumentDtos.VersionSummary;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.template.TemplateService;
import eu.socle.template.TemplateService.TemplateInstantiation;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.trash.TrashService;
import eu.socle.user.UserSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private static final int MAX_VERSION_LIMIT = 200;

    private final DocumentRepository repository;
    private final DocumentStore documentStore;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ReliabilityScoreService reliabilityScoreService;
    private final TrashService trashService;
    private final TransclusionResolver transclusionResolver;
    private final StalenessService stalenessService;
    private final JdbcTemplate jdbc;
    private final DocumentLinkService documentLinkService;
    private final TemplateService templateService;
    private ActivityEventService activityEventService;

    /**
     * Constructeur tests unitaires — provider relational implicite ;
     * {@link TransclusionResolver} créé avec le même store.
     */
    public DocumentService(
            DocumentRepository repository,
            DocumentVersionRepository versionRepository,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            ReliabilityScoreService reliabilityScoreService,
            TrashService trashService
    ) {
        this(
                repository,
                userSyncService,
                authorizationService,
                auditService,
                reliabilityScoreService,
                trashService,
                new RelationalDocumentStore(versionRepository),
                null,
                null,
                null,
                null
        );
    }

    /** Constructeur sans modèles de pages (tests existants) — {@code templateId} → 503. */
    public DocumentService(
            DocumentRepository repository,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            ReliabilityScoreService reliabilityScoreService,
            TrashService trashService,
            DocumentStore documentStore,
            TransclusionResolver transclusionResolver,
            StalenessService stalenessService,
            JdbcTemplate jdbc,
            DocumentLinkService documentLinkService
    ) {
        this(
                repository,
                userSyncService,
                authorizationService,
                auditService,
                reliabilityScoreService,
                trashService,
                documentStore,
                transclusionResolver,
                stalenessService,
                jdbc,
                documentLinkService,
                null);
    }

    @Autowired
    public DocumentService(
            DocumentRepository repository,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            ReliabilityScoreService reliabilityScoreService,
            TrashService trashService,
            DocumentStore documentStore,
            TransclusionResolver transclusionResolver,
            StalenessService stalenessService,
            JdbcTemplate jdbc,
            DocumentLinkService documentLinkService,
            TemplateService templateService
    ) {
        this.templateService = templateService;
        this.repository = repository;
        this.documentStore = documentStore;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.reliabilityScoreService = reliabilityScoreService;
        this.trashService = trashService;
        this.jdbc = jdbc;
        this.documentLinkService = documentLinkService;
        this.transclusionResolver = transclusionResolver != null
                ? transclusionResolver
                : new TransclusionResolver(
                        repository,
                        documentStore,
                        authorizationService,
                        new ExternalReferencePolicy(null) {
                            @Override
                            public boolean allowsInterWorkspaceEdge(
                                    java.util.UUID sourceSpaceId, java.util.UUID targetSpaceId) {
                                return true;
                            }

                            @Override
                            public String readMode(java.util.UUID spaceId) {
                                return ExternalReferencePolicy.OPEN;
                            }
                        },
                        (source, target) -> { });
        this.stalenessService = stalenessService != null
                ? stalenessService
                : new StalenessService(documentStore, new StalenessProperties());
    }

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_AUTHZ_REFILL_ITERATIONS = 3;

    @Transactional(readOnly = true)
    public DocumentListPage list(Jwt jwt, Integer limit, Integer offset) {
        var user = userSyncService.syncFromJwt(jwt);
        int lim = limit == null ? DEFAULT_LIST_LIMIT : Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        int off = offset == null ? 0 : Math.max(offset, 0);
        DocumentScope global = DocumentScope.global();

        int estimatedTotal = authorizationService.countPreselectedDocuments(user.getId(), global);

        List<UUID> accepted = new ArrayList<>();
        int scanOffset = off;
        int iterations = 0;
        boolean moreCandidatesPossible = true;
        while (accepted.size() < lim && iterations < MAX_AUTHZ_REFILL_ITERATIONS) {
            iterations++;
            List<UUID> batch = authorizationService.preselectDocumentIdsPage(
                    user.getId(), global, lim, scanOffset);
            if (batch.isEmpty()) {
                moreCandidatesPossible = false;
                break;
            }
            List<UUID> allowed = authorizationService.filterByDocumentViewer(
                    user.getId(), batch, "documents-list");
            Set<UUID> allowedSet = new LinkedHashSet<>(allowed);
            for (UUID id : batch) {
                if (allowedSet.contains(id) && accepted.size() < lim) {
                    accepted.add(id);
                }
            }
            scanOffset += batch.size();
            if (batch.size() < lim) {
                moreCandidatesPossible = false;
                break;
            }
        }

        String warning = null;
        if (accepted.size() < lim
                && moreCandidatesPossible
                && iterations >= MAX_AUTHZ_REFILL_ITERATIONS) {
            warning = "documents_authz_refill_truncated";
        }

        if (accepted.isEmpty()) {
            return new DocumentListPage(List.of(), estimatedTotal, true, warning);
        }

        Map<UUID, DocumentEntity> byId = new HashMap<>();
        for (DocumentEntity d : repository.findAllActiveByIdIn(accepted)) {
            byId.put(d.getId(), d);
        }
        List<DocumentSummary> results = new ArrayList<>();
        for (UUID id : accepted) {
            DocumentEntity d = byId.get(id);
            if (d == null) {
                continue;
            }
            var f = stalenessService.freshness(d.getId(), d.getCreatedAt());
            results.add(new DocumentSummary(
                    d.getId(),
                    d.getTitle(),
                    d.getStatus(),
                    d.getUpdatedAt(),
                    d.getReliabilityScore(),
                    f.stale(),
                    f.contentModifiedAt()));
        }
        return new DocumentListPage(List.copyOf(results), estimatedTotal, true, warning);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Jwt jwt, UUID id) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), id, "viewer");
        return toResponse(require(id));
    }

    /**
     * Lecture composite : body avec blocs {@code transclusion} résolus dynamiquement.
     * Ré-vérifie OpenFGA {@code viewer} sur chaque cible ; aucun cache de contenu résolu.
     */
    @Transactional(readOnly = true)
    public DocumentResponse getResolved(Jwt jwt, UUID id) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), id, "viewer");
        DocumentEntity d = require(id);
        Map<String, Object> raw = documentStore.readCurrentContent(d.getId(), d.getBody());
        Map<String, Object> resolved = transclusionResolver.resolve(user.getId(), d.getId(), raw);
        return toResponse(d, resolved);
    }

    @Transactional
    public DocumentResponse create(Jwt jwt, CreateDocumentRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        if (request.spaceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spaceId requis");
        }
        UUID spaceId = request.spaceId();
        UUID folderId = request.folderId();
        if (folderId != null) {
            List<UUID> folderSpace = jdbc.query("""
                    SELECT space_id FROM folders WHERE id = ? AND deleted_at IS NULL
                    """,
                    (rs, i) -> (UUID) rs.getObject("space_id"), folderId);
            if (folderSpace.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
            }
            if (!folderSpace.getFirst().equals(spaceId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Le dossier n'appartient pas à cet espace");
            }
            authorizationService.requireFolderRelation(user.getId(), folderId, "editor");
        } else {
            authorizationService.requireSpaceRelation(user.getId(), spaceId, "editor");
        }

        String visibility = resolveCreateVisibility(user.getId(), spaceId, request.visibility());

        // Modèle : corps dérivé du template (variables substituées) ; sinon body requis.
        TemplateInstantiation template = null;
        if (request.templateId() != null) {
            if (templateService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Modèles de pages indisponibles");
            }
            template = templateService.prepareInstantiation(
                    user, request.templateId(), spaceId, request.title());
        } else if (request.body() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "body requis (ou templateId)");
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setSpaceId(spaceId);
        entity.setFolderId(folderId);
        entity.setTitle(request.title().trim());
        String requestedDocType = blankToNull(request.docType());
        entity.setDocType(requestedDocType != null
                ? requestedDocType
                : template != null ? blankToNull(template.docType()) : null);
        Map<String, Object> body = transclusionResolver.normalizeForStorage(
                copyBody(template != null ? template.body() : request.body()));
        entity.setBody(body);
        if (template != null) {
            entity.setTemplateId(template.templateId());
            entity.setTemplateVersion(template.version());
        }
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(1);
        entity.setVisibility(visibility);
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        DocumentEntity saved = repository.save(entity);

        // OpenFGA avant Git : si FGA échoue → rollback DB, aucun commit Git orphelin.
        // Si Git échoue après FGA → compensation (delete groupé) puis propagation.
        // Cas résiduel : compensation FGA échoue aussi → tuples orphelins (ALERT) ;
        // la TX DB est annulée ; réparer via visibility-drift / migrate-visibility.
        List<AuthorizationService.AccessTuple> provisioned =
                authorizationService.provisionDocumentAccess(
                        saved.getId(), spaceId, folderId, user.getId(), visibility);
        try {
            String headSha = documentStore.createContent(saved.getId(), body, user.getId());
            if (headSha != null) {
                saved.setGitHeadSha(headSha);
                saved = repository.save(saved);
            }
        } catch (RuntimeException e) {
            try {
                authorizationService.revokeDocumentAccess(provisioned);
            } catch (RuntimeException compensationFailure) {
                // Conserver l'échec Git ; log compensation pour intervention manuelle
                org.slf4j.LoggerFactory.getLogger(DocumentService.class).error(
                        "ALERT CRITICAL : compensation OpenFGA après échec Git createContent "
                                + "échouée — document:{} tuples potentiellement orphelins",
                        saved.getId(),
                        compensationFailure);
            }
            throw e;
        }

        syncDocumentLinks(saved, body);

        Map<String, Object> createMeta = new LinkedHashMap<>();
        createMeta.put("title", saved.getTitle());
        createMeta.put("spaceId", saved.getSpaceId().toString());
        createMeta.put("status", saved.getStatus());
        createMeta.put("visibility", visibility);
        if (template != null) {
            createMeta.put("templateId", template.templateId().toString());
            createMeta.put("templateVersion", template.version());
        }
        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_CREATED,
                "document",
                saved.getId(),
                createMeta,
                null
        );
        if (template != null) {
            // Les INSERT JDBC (document_tags) référencent documents(id) : flush JPA d'abord.
            repository.flush();
            templateService.afterDocumentCreated(user.getId(), template, saved.getId(), spaceId);
        }
        return toResponse(saved);
    }

    /**
     * Change la visibilité — owners uniquement. Tuples FGA atomiques avec rollback colonne si échec.
     */
    @Transactional
    public DocumentResponse updateVisibility(Jwt jwt, UUID id, String rawVisibility) {
        var user = userSyncService.syncFromJwt(jwt);
        String to = DocumentVisibility.requireValid(rawVisibility);
        authorizationService.requireDocumentRelation(user.getId(), id, "owner");
        DocumentEntity entity = repository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        String from = entity.getVisibility() == null ? DocumentVisibility.SPACE : entity.getVisibility();
        if (from.equals(to)) {
            return toResponse(entity);
        }

        String parentObject = resolveParentObject(entity);
        entity.setVisibility(to);
        entity.touch();
        DocumentEntity saved = repository.save(entity);
        // Échec FGA → rollback transactionnel de la colonne visibility.
        authorizationService.applyVisibilityTuples(saved.getId(), parentObject, from, to);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", from);
        meta.put("to", to);
        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_VISIBILITY_CHANGED,
                "document",
                saved.getId(),
                meta,
                null
        );
        return toResponse(saved);
    }

    private String resolveCreateVisibility(UUID userId, UUID spaceId, String requested) {
        String spaceDefault = DocumentVisibility.ORGANISATION;
        if (jdbc != null) {
            String dv = jdbc.query(
                    "SELECT default_visibility FROM spaces WHERE id = ? AND deleted_at IS NULL",
                    rs -> rs.next() ? rs.getString("default_visibility") : null,
                    spaceId);
            if (dv != null && !dv.isBlank()) {
                spaceDefault = DocumentVisibility.requireValid(dv);
            }
        }
        if (requested == null || requested.isBlank()) {
            return spaceDefault;
        }
        String v = DocumentVisibility.requireValid(requested);
        if (!v.equals(spaceDefault)
                && !authorizationService.hasRelation(userId, "space", spaceId, "owner")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Seul un owner de l'espace peut surcharger la visibilité à la création");
        }
        return v;
    }

    private String resolveParentObject(DocumentEntity entity) {
        if (entity.getFolderId() != null) {
            return "folder:" + entity.getFolderId();
        }
        return "space:" + entity.getSpaceId();
    }

    /**
     * Update : archive l'ancien body, applique la règle de statut, incrémente la version —
     * même transaction.
     */
    @Transactional
    public DocumentResponse update(Jwt jwt, UUID id, UpdateDocumentRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), id, "editor");
        DocumentEntity entity = repository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        assertExpectedVersion(entity, request.expectedVersionNo());
        String previousTitle = entity.getTitle();
        Map<String, Object> previousBody = copyBody(
                documentStore.readCurrentContent(entity.getId(), entity.getBody()));
        int archivedVersionNo = entity.getCurrentVersionNo();
        String expectedGitHead = entity.getGitHeadSha();

        StatusTransition statusTransition = applyBodyMutationStatusRules(entity);

        UUID contentAuthorId = contentAuthorOf(entity);
        documentStore.archiveVersion(
                entity.getId(),
                archivedVersionNo,
                previousBody,
                contentAuthorId,
                user.getId(),
                blankToNull(request.changeSummary())
        );

        Map<String, Object> newBody = transclusionResolver.normalizeForStorage(copyBody(request.body()));
        entity.setTitle(request.title().trim());
        if (request.docType() != null) {
            entity.setDocType(blankToNull(request.docType()));
        }
        entity.setBody(newBody);
        entity.setCurrentVersionNo(archivedVersionNo + 1);
        entity.setUpdatedBy(user.getId());
        entity.touch();
        DocumentEntity saved = repository.save(entity);
        String newHeadSha = documentStore.writeCurrentContent(
                saved.getId(),
                newBody,
                user.getId(),
                user.getId(),
                blankToNull(request.changeSummary()),
                expectedGitHead);
        if (newHeadSha != null) {
            saved.setGitHeadSha(newHeadSha);
            saved = repository.save(saved);
        }

        syncDocumentLinks(saved, newBody);

        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_VERSION_CREATED,
                "document",
                saved.getId(),
                Map.of(
                        "versionNo", archivedVersionNo,
                        "newCurrentVersionNo", saved.getCurrentVersionNo()
                ),
                null
        );
        Map<String, Object> updateMeta = new LinkedHashMap<>();
        updateMeta.put("previousTitle", previousTitle);
        updateMeta.put("title", saved.getTitle());
        updateMeta.put("status", saved.getStatus());
        updateMeta.put("currentVersionNo", saved.getCurrentVersionNo());
        putStatusTransition(updateMeta, statusTransition);
        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_UPDATED,
                "document",
                saved.getId(),
                updateMeta,
                null
        );
        if (activityEventService != null
                && "valide".equals(statusTransition.before())
                && "en_revue".equals(statusTransition.after())) {
            activityEventService.record(
                    ActivityEventTypes.EDIT_PROPOSAL,
                    user.getId(),
                    saved.getId(),
                    saved.getSpaceId(),
                    Map.of("title", saved.getTitle()));
        }
        return toResponse(saved);
    }

    /**
     * Contrôle de version côté client (symétrique à {@code git_head_sha} en mode Git).
     * Absent avant cette tâche en relational — voir {@code docs/editing-concurrency.md}.
     */
    static void assertExpectedVersion(DocumentEntity entity, Integer expectedVersionNo) {
        if (expectedVersionNo == null) {
            return;
        }
        if (expectedVersionNo != entity.getCurrentVersionNo()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document modifié depuis le chargement (attendu v" + expectedVersionNo
                            + ", actuel v" + entity.getCurrentVersionNo() + ")");
        }
    }

    @Transactional(readOnly = true)
    public VersionPage listVersions(Jwt jwt, UUID documentId, int offset, int limit) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        require(documentId);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_VERSION_LIMIT);
        int safeOffset = Math.max(offset, 0);
        int page = safeOffset / safeLimit;
        Page<DocumentStore.StoredVersion> result = documentStore.listVersions(documentId, page, safeLimit);
        List<VersionSummary> items = result.getContent().stream()
                .map(v -> new VersionSummary(
                        v.versionNo(),
                        v.authorId(),
                        v.archivedBy(),
                        v.changeSummary(),
                        v.createdAt()))
                .toList();
        return new VersionPage(items, safeOffset, safeLimit, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public VersionDetail getVersion(Jwt jwt, UUID documentId, int versionNo) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        DocumentStore.StoredVersion version = documentStore.findVersion(documentId, versionNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Version " + versionNo + " introuvable pour ce document"));
        Map<String, Object> versionBody = documentStore.loadVersionBody(documentId, versionNo);
        return new VersionDetail(
                version.documentId(),
                version.versionNo(),
                versionBody,
                version.authorId(),
                version.archivedBy(),
                version.changeSummary(),
                version.createdAt()
        );
    }

    @Transactional(readOnly = true)
    public VersionDiffResponse diff(Jwt jwt, UUID documentId, int versionA, int versionB) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        DocumentStore.VersionDiffResult result = documentStore.diff(documentId, versionA, versionB);
        List<DiffChange> changes = result.changes().stream()
                .map(c -> new DiffChange(c.path(), c.op(), c.before(), c.after()))
                .toList();
        return new VersionDiffResponse(documentId, result.fromVersion(), result.toVersion(), changes);
    }

    /**
     * Restore append-only : archive le body courant, applique la règle de statut, remplace
     * par le snapshot cible — même transaction.
     * Même contrôle de version que {@link #update} ({@code expectedVersionNo} → 409).
     */
    @Transactional
    public DocumentResponse restore(Jwt jwt, UUID documentId, int versionNo, Integer expectedVersionNo) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        DocumentEntity entity = repository.findActiveByIdForUpdate(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        assertExpectedVersion(entity, expectedVersionNo);
        StatusTransition statusTransition = applyBodyMutationStatusRules(entity);
        Map<String, Object> targetBody = transclusionResolver.normalizeForStorage(
                documentStore.loadVersionBody(documentId, versionNo));

        Map<String, Object> previousBody = copyBody(
                documentStore.readCurrentContent(entity.getId(), entity.getBody()));
        int archivedVersionNo = entity.getCurrentVersionNo();
        String expectedGitHead = entity.getGitHeadSha();
        String summary = "Restauration de la version " + versionNo;

        UUID contentAuthorId = contentAuthorOf(entity);
        documentStore.archiveVersion(
                entity.getId(),
                archivedVersionNo,
                previousBody,
                contentAuthorId,
                user.getId(),
                summary
        );

        entity.setBody(copyBody(targetBody));
        entity.setCurrentVersionNo(archivedVersionNo + 1);
        entity.setUpdatedBy(user.getId());
        entity.touch();
        DocumentEntity saved = repository.save(entity);
        String newHeadSha = documentStore.writeCurrentContent(
                saved.getId(),
                copyBody(targetBody),
                user.getId(),
                user.getId(),
                summary,
                expectedGitHead);
        if (newHeadSha != null) {
            saved.setGitHeadSha(newHeadSha);
            saved = repository.save(saved);
        }

        syncDocumentLinks(saved, copyBody(targetBody));

        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_VERSION_CREATED,
                "document",
                saved.getId(),
                Map.of(
                        "versionNo", archivedVersionNo,
                        "newCurrentVersionNo", saved.getCurrentVersionNo(),
                        "reason", "restore"
                ),
                null
        );
        Map<String, Object> restoreMeta = new LinkedHashMap<>();
        restoreMeta.put("restoredFromVersion", versionNo);
        restoreMeta.put("archivedVersionNo", archivedVersionNo);
        restoreMeta.put("currentVersionNo", saved.getCurrentVersionNo());
        putStatusTransition(restoreMeta, statusTransition);
        auditService.record(
                user.getId(),
                false,
                AuditActions.DOCUMENT_VERSION_RESTORED,
                "document",
                saved.getId(),
                restoreMeta,
                null
        );
        return toResponse(saved);
    }

    private void syncDocumentLinks(DocumentEntity doc, Map<String, Object> tipTapBody) {
        if (documentLinkService == null || doc == null) {
            return;
        }
        documentLinkService.replaceOutgoingLinks(doc.getId(), doc.getSpaceId(), tipTapBody);
    }

    /** Soft-delete (corbeille) — délégué à {@link TrashService}. */
    @Transactional
    public TrashService.SoftDeleteResult softDelete(Jwt jwt, UUID id) {
        return trashService.softDeleteDocument(jwt, id);
    }

    /**
     * Règle unique pour toute mutation de {@code documents.body} (update + restore) :
     * <ul>
     *   <li>{@code archive} → rejet 409 (pas de mutation silencieuse)</li>
     *   <li>{@code valide} → bascule {@code en_revue} (exige une nouvelle approbation)</li>
     *   <li>autres statuts → inchangés</li>
     * </ul>
     */
    StatusTransition applyBodyMutationStatusRules(DocumentEntity entity) {
        String before = entity.getStatus();
        if ("archive".equals(before)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document archivé : modification de contenu interdite");
        }
        if ("valide".equals(before)) {
            entity.setStatus("en_revue");
            reliabilityScoreService.clearScoreOnEntity(entity);
            return new StatusTransition(before, "en_revue");
        }
        return new StatusTransition(before, before);
    }

    private static void putStatusTransition(Map<String, Object> meta, StatusTransition transition) {
        meta.put("status_before", transition.before());
        meta.put("status_after", transition.after());
    }

    private DocumentEntity require(UUID id) {
        return repository.findActiveById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
    }

    private DocumentResponse toResponse(DocumentEntity d) {
        Map<String, Object> body = documentStore.readCurrentContent(d.getId(), d.getBody());
        return toResponse(d, body);
    }

    private DocumentResponse toResponse(DocumentEntity d, Map<String, Object> body) {
        var f = stalenessService.freshness(d.getId(), d.getCreatedAt());
        String vis = d.getVisibility() == null ? DocumentVisibility.SPACE : d.getVisibility();
        return new DocumentResponse(
                d.getId(),
                d.getSpaceId(),
                d.getFolderId(),
                d.getTitle(),
                d.getDocType(),
                body,
                d.getStatus(),
                d.getCurrentVersionNo(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getReliabilityScore(),
                d.getReliabilityComputedAt(),
                f.stale(),
                f.contentModifiedAt(),
                f.thresholdDays(),
                vis,
                d.getPosition(),
                d.getTemplateId(),
                d.getTemplateVersion()
        );
    }

    private static Map<String, Object> copyBody(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        return new HashMap<>(body);
    }

    /** Auteur du contenu courant avant mutation ({@code updated_by}, sinon {@code created_by}). */
    static UUID contentAuthorOf(DocumentEntity entity) {
        if (entity.getUpdatedBy() != null) {
            return entity.getUpdatedBy();
        }
        return entity.getCreatedBy();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Transition de statut appliquée (ou no-op) lors d'une mutation de body. */
    record StatusTransition(String before, String after) {
        boolean changed() {
            return before != null && !before.equals(after);
        }
    }
}
