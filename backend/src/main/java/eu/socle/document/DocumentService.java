// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.activity.ActivityEventService;
import eu.socle.activity.ActivityEventTypes;
import eu.socle.attachment.AttachmentService;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.DiffChange;
import eu.socle.document.DocumentDtos.DocumentListPage;
import eu.socle.document.DocumentDtos.DocumentPermissions;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.DocumentSummary;
import eu.socle.document.DocumentDtos.PersonRef;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentDtos.VersionCompareResponse;
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
import eu.socle.web.ApiErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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
    private AttachmentService attachmentService;
    private eu.socle.poll.PollService pollService;
    private EditLockService editLockService;
    private DiffProperties diffProperties;
    private TipTapContentValidator tipTapContentValidator = new TipTapContentValidator();

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

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Autowired(required = false)
    void setPollService(eu.socle.poll.PollService pollService) {
        this.pollService = pollService;
    }

    /** Verrou d'édition : une restauration ne doit pas écraser le travail d'un autre éditeur actif. */
    @Autowired(required = false)
    void setEditLockService(EditLockService editLockService) {
        this.editLockService = editLockService;
    }

    @Autowired(required = false)
    void setDiffProperties(DiffProperties diffProperties) {
        this.diffProperties = diffProperties;
    }

    @Autowired(required = false)
    void setTipTapContentValidator(TipTapContentValidator tipTapContentValidator) {
        if (tipTapContentValidator != null) {
            this.tipTapContentValidator = tipTapContentValidator;
        }
    }

    private Map<String, Object> normalizeAndValidateBody(Map<String, Object> body) {
        Map<String, Object> normalized = transclusionResolver.normalizeForStorage(body);
        tipTapContentValidator.validate(normalized);
        return normalized;
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
        return toResponse(require(id), user.getId());
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
        return toResponse(d, resolved, user.getId());
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
        Map<String, Object> body = normalizeAndValidateBody(
                copyBody(template != null ? template.body() : request.body()));
        entity.setBody(body);
        if (template != null) {
            entity.setTemplateId(template.templateId());
            entity.setTemplateVersion(template.version());
        }
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(1);
        entity.setCurrentChangeSummary(resolveChangeSummary(
                request.changeSummary(), 1, Map.of(), body));
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
            String headSha = documentStore.createContent(
                    saved.getId(), body, user.getId(), saved.getCurrentChangeSummary());
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
        markAttachmentsReferenced(saved.getId(), body);

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
        return toResponse(saved, user.getId());
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
            return toResponse(entity, user.getId());
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
        return toResponse(saved, user.getId());
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
        assertNoApprovalInProgress(id);
        String previousTitle = entity.getTitle();
        Map<String, Object> previousBody = copyBody(
                documentStore.readCurrentContent(entity.getId(), entity.getBody()));
        int archivedVersionNo = entity.getCurrentVersionNo();
        String expectedGitHead = entity.getGitHeadSha();

        StatusTransition statusTransition = applyBodyMutationStatusRules(entity);

        UUID contentAuthorId = contentAuthorOf(entity);
        String archivedSummary = entity.getCurrentChangeSummary();
        Map<String, Object> newBody = normalizeAndValidateBody(copyBody(request.body()));
        String newSummary = resolveChangeSummary(
                request.changeSummary(), archivedVersionNo + 1, previousBody, newBody);
        documentStore.archiveVersion(
                entity.getId(),
                archivedVersionNo,
                previousBody,
                contentAuthorId,
                user.getId(),
                archivedSummary
        );

        entity.setTitle(request.title().trim());
        if (request.docType() != null) {
            entity.setDocType(blankToNull(request.docType()));
        }
        entity.setBody(newBody);
        entity.setCurrentVersionNo(archivedVersionNo + 1);
        entity.setCurrentChangeSummary(newSummary);
        entity.setUpdatedBy(user.getId());
        entity.touch();
        DocumentEntity saved = repository.save(entity);
        String newHeadSha = documentStore.writeCurrentContent(
                saved.getId(),
                newBody,
                user.getId(),
                user.getId(),
                newSummary,
                expectedGitHead);
        if (newHeadSha != null) {
            saved.setGitHeadSha(newHeadSha);
            saved = repository.save(saved);
        }

        syncDocumentLinks(saved, newBody);
        markAttachmentsReferenced(saved.getId(), newBody);
        discardDraft(saved.getId(), user.getId());

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
        return toResponse(saved, user.getId());
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
        DocumentEntity document = require(documentId);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_VERSION_LIMIT);
        int safeOffset = Math.max(offset, 0);
        long archivedTotal = documentStore.countVersions(documentId);
        long total = archivedTotal + 1;

        // Page 0 : version courante en tête ; pages suivantes : offset archivé = offset API − 1.
        List<DocumentStore.StoredVersion> archivedSlice;
        boolean includeCurrent = safeOffset == 0;
        if (includeCurrent) {
            int archivedLimit = Math.max(safeLimit - 1, 0);
            archivedSlice = archivedLimit == 0
                    ? List.of()
                    : documentStore.listVersionsFromOffset(documentId, 0, archivedLimit).getContent();
        } else {
            archivedSlice = documentStore
                    .listVersionsFromOffset(documentId, safeOffset - 1, safeLimit)
                    .getContent();
        }

        List<UUID> authorIds = new ArrayList<>();
        UUID currentAuthorId = contentAuthorOf(document);
        if (includeCurrent && currentAuthorId != null) {
            authorIds.add(currentAuthorId);
        }
        for (DocumentStore.StoredVersion v : archivedSlice) {
            if (v.authorId() != null) {
                authorIds.add(v.authorId());
            }
        }
        Map<UUID, PersonRef> people = resolvePeople(authorIds.stream().distinct().toArray(UUID[]::new));

        int maxLines = diffMaxLines();
        Map<Integer, List<String>> linesCache = new HashMap<>();
        List<VersionSummary> items = new ArrayList<>(safeLimit);
        if (includeCurrent) {
            items.add(toVersionSummary(
                    document,
                    document.getCurrentVersionNo(),
                    currentAuthorId,
                    null,
                    resolveCurrentChangeSummary(document),
                    document.getUpdatedAt() != null
                            ? document.getUpdatedAt()
                            : documentStore.lastContentModifiedAt(documentId, document.getCreatedAt()),
                    true,
                    people,
                    linesCache,
                    maxLines));
        }
        for (DocumentStore.StoredVersion v : archivedSlice) {
            items.add(toVersionSummary(
                    document,
                    v.versionNo(),
                    v.authorId(),
                    v.archivedBy(),
                    v.changeSummary(),
                    v.createdAt(),
                    false,
                    people,
                    linesCache,
                    maxLines));
        }
        return new VersionPage(List.copyOf(items), safeOffset, safeLimit, total);
    }

    /** Source unique du résumé courant (relational et git) : {@code documents.current_change_summary}. */
    private String resolveCurrentChangeSummary(DocumentEntity document) {
        return document.getCurrentChangeSummary();
    }

    private VersionSummary toVersionSummary(
            DocumentEntity document,
            int versionNo,
            UUID authorId,
            UUID archivedBy,
            String changeSummary,
            java.time.Instant createdAt,
            boolean current,
            Map<UUID, PersonRef> people,
            Map<Integer, List<String>> linesCache,
            int maxLines
    ) {
        int[] stats = lineStats(document, versionNo, linesCache, maxLines);
        String name = VersionSummary.SYSTEM_AUTHOR_LABEL;
        String initials = VersionSummary.SYSTEM_AUTHOR_INITIALS;
        if (authorId != null) {
            PersonRef p = people.get(authorId);
            if (p == null) {
                p = PersonRef.deleted(authorId);
            }
            name = p.displayName();
            initials = p.initials();
        }
        return new VersionSummary(
                versionNo,
                authorId,
                archivedBy,
                changeSummary,
                createdAt,
                name,
                initials,
                stats[0],
                stats[1],
                current);
    }

    /**
     * {@code [added, removed]} de la version {@code versionNo} vs la précédente ({@code n-1}).
     * Sans version précédente (v1, trou de numérotation) : tout le contenu est « ajouté ».
     * Au-delà du plafond de lignes : approximation par différence de taille (pas de diff).
     */
    private int[] lineStats(
            DocumentEntity document,
            int versionNo,
            Map<Integer, List<String>> cache,
            int maxLines
    ) {
        List<String> current = versionLines(document, versionNo, cache);
        if (current == null) {
            return new int[] {0, 0};
        }
        List<String> previous = versionNo > 1 ? versionLines(document, versionNo - 1, cache) : null;
        if (previous == null) {
            return new int[] {MarkdownLineDiff.nonBlankCount(current), 0};
        }
        if (current.size() > maxLines || previous.size() > maxLines) {
            int delta = MarkdownLineDiff.nonBlankCount(current) - MarkdownLineDiff.nonBlankCount(previous);
            return new int[] {Math.max(delta, 0), Math.max(-delta, 0)};
        }
        return MarkdownLineDiff.stats(previous, current);
    }

    private List<String> versionLines(
            DocumentEntity document,
            int versionNo,
            Map<Integer, List<String>> cache
    ) {
        if (cache.containsKey(versionNo)) {
            return cache.get(versionNo);
        }
        List<String> lines = null;
        if (versionNo == document.getCurrentVersionNo()) {
            lines = MarkdownLineDiff.linesOf(
                    documentStore.readCurrentContent(document.getId(), document.getBody()));
        } else if (documentStore.findVersion(document.getId(), versionNo).isPresent()) {
            lines = MarkdownLineDiff.linesOf(documentStore.loadVersionBody(document.getId(), versionNo));
        }
        cache.put(versionNo, lines);
        return lines;
    }

    private int diffMaxLines() {
        return diffProperties == null ? DiffProperties.DEFAULT_MAX_LINES : diffProperties.getMaxLines();
    }

    /**
     * Comparaison Markdown ligne à ligne de deux versions (archivées ou courante) du corps
     * <strong>stocké</strong> — les transclusions ne sont jamais résolues. Viewer suffit.
     */
    @Transactional(readOnly = true)
    public VersionCompareResponse compare(Jwt jwt, UUID documentId, int versionA, int versionB, String mode) {
        if (mode != null && !mode.isBlank() && !"lines".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode non supporté : " + mode);
        }
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        DocumentEntity document = require(documentId);
        int maxLines = diffMaxLines();
        List<String> before = compareLines(document, versionA, maxLines);
        List<String> after = compareLines(document, versionB, maxLines);
        MarkdownLineDiff.Result diff = MarkdownLineDiff.compare(before, after);
        return new VersionCompareResponse(
                documentId, versionA, versionB, diff.added(), diff.removed(), diff.hunks());
    }

    private List<String> compareLines(DocumentEntity document, int versionNo, int maxLines) {
        Map<String, Object> body;
        if (versionNo == document.getCurrentVersionNo()) {
            body = documentStore.readCurrentContent(document.getId(), document.getBody());
        } else {
            documentStore.findVersion(document.getId(), versionNo)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Version " + versionNo + " introuvable pour ce document"));
            body = documentStore.loadVersionBody(document.getId(), versionNo);
        }
        List<String> lines = MarkdownLineDiff.linesOf(body);
        if (lines.size() > maxLines) {
            throw ApiErrors.diffTooLarge(versionNo, lines.size(), maxLines);
        }
        return lines;
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
        // Document archivé → 409 (règle commune update/restore) ; l'éventuelle bascule de statut
        // est annulée avec la transaction si une règle ci-dessous échoue.
        StatusTransition statusTransition = applyBodyMutationStatusRules(entity);
        // 404 : version cible inexistante (ou version courante, non archivée).
        documentStore.findVersion(documentId, versionNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Version " + versionNo + " introuvable pour ce document"));
        assertRestoreAllowed(documentId, user.getId());
        Map<String, Object> targetBody = normalizeAndValidateBody(
                documentStore.loadVersionBody(documentId, versionNo));

        Map<String, Object> previousBody = copyBody(
                documentStore.readCurrentContent(entity.getId(), entity.getBody()));
        int archivedVersionNo = entity.getCurrentVersionNo();
        String expectedGitHead = entity.getGitHeadSha();
        String archivedSummary = entity.getCurrentChangeSummary();
        String summary = "Restauration de la version " + versionNo;

        UUID contentAuthorId = contentAuthorOf(entity);
        documentStore.archiveVersion(
                entity.getId(),
                archivedVersionNo,
                previousBody,
                contentAuthorId,
                user.getId(),
                archivedSummary
        );

        entity.setBody(copyBody(targetBody));
        entity.setCurrentVersionNo(archivedVersionNo + 1);
        entity.setCurrentChangeSummary(summary);
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
        markAttachmentsReferenced(saved.getId(), copyBody(targetBody));
        // Comme update : la restauration est une sauvegarde explicite — brouillon du restaurateur
        // consommé ; les brouillons des autres utilisateurs ne sont pas touchés.
        discardDraft(saved.getId(), user.getId());

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
        return toResponse(saved, user.getId());
    }

    /**
     * Garde-fous de restauration (409) :
     * <ul>
     *   <li>un <em>autre</em> utilisateur détient un verrou d'édition actif (le restaurateur
     *       lui-même, ou l'absence de verrou, sont acceptés) ;</li>
     *   <li>une demande d'approbation est en cours (même message que les étiquettes gouvernées).</li>
     * </ul>
     */
    private void assertRestoreAllowed(UUID documentId, UUID restorerId) {
        if (editLockService != null) {
            editLockService.activeHolderOtherThan(documentId, restorerId).ifPresent(lock -> {
                throw ApiErrors.editLockHeld(lock.holderDisplayName());
            });
        }
        assertNoApprovalInProgress(documentId);
    }

    /**
     * Mutation de body (update / restore) interdite pendant une demande {@code en_cours}.
     * Même message que les étiquettes gouvernées. Les brouillons autosave restent permis.
     */
    private void assertNoApprovalInProgress(UUID documentId) {
        if (jdbc == null) {
            return;
        }
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE document_id = ? AND status = 'en_cours'",
                Integer.class, documentId);
        if (pending != null && pending > 0) {
            throw ApiErrors.approvalInProgress();
        }
    }

    /**
     * Sauvegarde explicite réussie : le brouillon autosave de l'auteur est consommé (même
     * transaction — rollback de la version = brouillon conservé).
     */
    private void discardDraft(UUID documentId, UUID userId) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM document_drafts WHERE document_id = ? AND user_id = ?", documentId, userId);
    }

    private void syncDocumentLinks(DocumentEntity doc, Map<String, Object> tipTapBody) {
        if (documentLinkService == null || doc == null) {
            return;
        }
        documentLinkService.replaceOutgoingLinks(doc.getId(), doc.getSpaceId(), tipTapBody);
    }

    private void markAttachmentsReferenced(UUID documentId, Map<String, Object> tipTapBody) {
        if (attachmentService == null || documentId == null || tipTapBody == null) {
            return;
        }
        attachmentService.markReferenced(documentId, tipTapBody);
        if (pollService != null) {
            pollService.syncFromBody(documentId, tipTapBody);
        }
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

    private DocumentResponse toResponse(DocumentEntity d, UUID callerId) {
        Map<String, Object> body = documentStore.readCurrentContent(d.getId(), d.getBody());
        return toResponse(d, body, callerId);
    }

    private DocumentResponse toResponse(DocumentEntity d, Map<String, Object> body, UUID callerId) {
        var f = stalenessService.freshness(d.getId(), d.getCreatedAt());
        String vis = d.getVisibility() == null ? DocumentVisibility.SPACE : d.getVisibility();
        SpaceContext spaceContext = loadSpaceContext(d.getSpaceId());
        // Une seule requête users pour createdBy / updatedBy / owner.
        Map<UUID, PersonRef> people = resolvePeople(
                d.getCreatedBy(), d.getUpdatedBy(), spaceContext.ownerId());
        DocumentPermissions permissions = computePermissions(callerId, d, spaceContext);
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
                d.getTemplateVersion(),
                personOf(people, d.getCreatedBy()),
                personOf(people, d.getUpdatedBy()),
                personOf(people, spaceContext.ownerId()),
                loadTags(d.getId()),
                permissions
        );
    }

    /** Statuts {@code users.status} traités comme compte supprimé / anonymisé (RGPD). */
    /** Compte anonymisé RGPD ou marqué deleted — pas un simple {@code disabled}. */
    static final Set<String> REMOVED_USER_STATUSES = Set.of("deleted", "anonymized");

    /** Données d'espace nécessaires à la réponse : politique de commentaire + owner résolu. */
    record SpaceContext(String commentPolicy, UUID ownerId) {
        static final SpaceContext EMPTY = new SpaceContext("members", null);
    }

    /**
     * Une requête : {@code comment_policy} et propriétaire résolu (responsable en priorité,
     * sinon le plus ancien owner utilisateur de {@code space_owners}).
     */
    private SpaceContext loadSpaceContext(UUID spaceId) {
        if (jdbc == null || spaceId == null) {
            return SpaceContext.EMPTY;
        }
        Object raw = jdbc.query("""
                SELECT s.comment_policy,
                       (SELECT so.user_id
                          FROM space_owners so
                         WHERE so.space_id = s.id
                         ORDER BY so.is_responsible DESC, so.created_at ASC, so.user_id ASC
                         LIMIT 1) AS owner_id
                  FROM spaces s
                 WHERE s.id = ? AND s.deleted_at IS NULL
                """,
                (ResultSetExtractor<Object>) rs -> {
                    if (!rs.next()) {
                        return SpaceContext.EMPTY;
                    }
                    String policy = rs.getString("comment_policy");
                    return new SpaceContext(
                            policy == null || policy.isBlank() ? "members" : policy,
                            (UUID) rs.getObject("owner_id"));
                },
                spaceId);
        return raw instanceof SpaceContext ctx ? ctx : SpaceContext.EMPTY;
    }

    /**
     * Résout les personnes en <strong>une seule</strong> requête groupée sur {@code users}.
     * Id absent de la map = id null (le champ de réponse reste null).
     * Compte supprimé / anonymisé ou ligne introuvable → « Utilisateur supprimé ».
     */
    private Map<UUID, PersonRef> resolvePeople(UUID... ids) {
        Set<UUID> wanted = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty() || jdbc == null) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        Map<UUID, PersonRef> found = new HashMap<>();
        Object queried = jdbc.query(
                "SELECT id, display_name, avatar_initials, status FROM users WHERE id IN ("
                        + placeholders + ")",
                (ResultSetExtractor<Object>) rs -> {
                    while (rs.next()) {
                        UUID id = (UUID) rs.getObject("id");
                        found.put(id, toPersonRef(
                                id,
                                rs.getString("display_name"),
                                rs.getString("avatar_initials"),
                                rs.getString("status")));
                    }
                    return Boolean.TRUE;
                },
                wanted.toArray());
        if (!Boolean.TRUE.equals(queried)) {
            return Map.of();
        }
        Map<UUID, PersonRef> result = new HashMap<>(found);
        for (UUID id : wanted) {
            result.computeIfAbsent(id, PersonRef::deleted);
        }
        return result;
    }

    static PersonRef toPersonRef(UUID id, String displayName, String avatarInitials, String status) {
        if (status != null && REMOVED_USER_STATUSES.contains(status.toLowerCase(java.util.Locale.ROOT))) {
            return PersonRef.deleted(id);
        }
        String name = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (name == null) {
            return PersonRef.deleted(id);
        }
        String initials = avatarInitials == null || avatarInitials.isBlank()
                ? PersonRef.initialsOf(name)
                : avatarInitials.trim();
        return new PersonRef(id, name, initials);
    }

    private static PersonRef personOf(Map<UUID, PersonRef> people, UUID id) {
        return id == null ? null : people.get(id);
    }

    /**
     * Droits d'affichage — un seul BatchCheck OpenFGA ({@code owner/editor/viewer} document,
     * {@code owner/viewer} espace). Les règles reproduisent celles des services d'action :
     * <ul>
     *   <li>canEdit : editor (inclut owner) sur le document</li>
     *   <li>canPublish : canEdit + statut {@code brouillon} + space viewer (cf. startApproval)</li>
     *   <li>canManageAccess : owner du document (hérité de l'espace) — cf. AccessController</li>
     *   <li>canComment : editor, ou politique {@code all_readers} → viewer doc, sinon space viewer
     *       (cf. CommentService)</li>
     *   <li>canManageAttestations : owner de l'espace (cf. AttestationService)</li>
     * </ul>
     */
    private DocumentPermissions computePermissions(UUID callerId, DocumentEntity d, SpaceContext space) {
        if (callerId == null || d.getSpaceId() == null) {
            return DocumentPermissions.NONE;
        }
        AuthorizationService.DocumentPermissionChecks c =
                authorizationService.batchCheckDocumentPermissions(callerId, d.getId(), d.getSpaceId());
        if (c == null) {
            return DocumentPermissions.NONE;
        }
        boolean canEdit = c.documentEditor() || c.documentOwner();
        boolean canPublish = canEdit && "brouillon".equals(d.getStatus()) && c.spaceViewer();
        boolean canManageAccess = c.documentOwner() || c.spaceOwner();
        boolean canComment = canEdit
                || ("all_readers".equals(space.commentPolicy()) ? c.documentViewer() : c.spaceViewer());
        boolean canManageAttestations = c.spaceOwner();
        return new DocumentPermissions(
                canEdit, canPublish, canManageAccess, canComment, canManageAttestations);
    }

    private List<DocumentDtos.TagRef> loadTags(UUID documentId) {
        if (jdbc == null) {
            return List.of();
        }
        List<DocumentDtos.TagRef> tags = jdbc.query("""
                SELECT t.id, t.name, t.color,
                       EXISTS (SELECT 1 FROM approval_role_assignments ara
                                WHERE ara.scope_type = 'tag'
                                  AND lower(ara.scope_ref) = t.id::text) AS governed
                  FROM document_tags dt
                  JOIN tags t ON t.id = dt.tag_id
                 WHERE dt.document_id = ?
                 ORDER BY t.name
                """,
                (rs, i) -> new DocumentDtos.TagRef(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getBoolean("governed")),
                documentId);
        return tags == null ? List.of() : tags;
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

    /**
     * Résumé de version : saisie client conservée telle quelle ; sinon déterministe
     * ({@link MarkdownLineDiff#CREATION_SUMMARY} pour v1, sinon auto diff).
     * La restauration fixe son propre libellé en amont.
     */
    private static String resolveChangeSummary(
            String requested,
            int newVersionNo,
            Map<String, Object> previousBody,
            Map<String, Object> newBody
    ) {
        String provided = blankToNull(requested);
        if (provided != null) {
            return provided;
        }
        if (newVersionNo <= 1) {
            return MarkdownLineDiff.CREATION_SUMMARY;
        }
        return MarkdownLineDiff.autoChangeSummary(
                previousBody != null ? previousBody : Map.of(),
                newBody != null ? newBody : Map.of());
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
