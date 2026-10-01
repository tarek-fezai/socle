// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentVisibility;
import eu.socle.document.TransclusionResolver;
import eu.socle.identity.IdentityFacade;
import eu.socle.storage.DocumentStore;
import eu.socle.template.TemplateDtos.CreateTemplateRequest;
import eu.socle.template.TemplateDtos.CreationWarning;
import eu.socle.template.TemplateDtos.CreationWarningsResponse;
import eu.socle.template.TemplateDtos.SaveAsTemplateRequest;
import eu.socle.template.TemplateDtos.SaveAsTemplateResponse;
import eu.socle.template.TemplateDtos.TemplateResponse;
import eu.socle.template.TemplateDtos.TemplateSummary;
import eu.socle.template.TemplateDtos.UpdateTemplateRequest;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Modèles de pages — globaux (administrateur système) ou d'espace (owners).
 *
 * <p>Le corps TipTap ({@code templates.body}) reste toujours en PostgreSQL, quel que soit
 * {@code socle.storage.provider}. Lecture = droit d'usage : global → tout utilisateur
 * authentifié ; espace → {@code space.viewer}. Un modèle illisible répond 404 (pas de fuite
 * d'existence).
 */
@Service
public class TemplateService {

    static final String TEMPLATE_RESOURCE = "template";
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    static final String WARNING_TRANSCLUSION_RESTRICTED = "transclusion_restricted";

    private static final String SELECT_COLUMNS = """
            t.id, t.name, t.description, t.doc_type, t.space_id, t.default_tag_ids,
            t.version, t.created_by, t.updated_by, t.created_at, t.updated_at
            """;
    private static final String ACTIVE_JOIN = """
              FROM templates t
              LEFT JOIN spaces s ON s.id = t.space_id
             WHERE t.deleted_at IS NULL
               AND (t.space_id IS NULL OR s.deleted_at IS NULL)
            """;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final IdentityFacade identityFacade;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final TransclusionResolver transclusionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TemplateService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            IdentityFacade identityFacade,
            AuthorizationService authorizationService,
            AuditService auditService,
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            TransclusionResolver transclusionResolver,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.identityFacade = identityFacade;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.transclusionResolver = transclusionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    /**
     * Modèles globaux + modèles de l'espace {@code spaceId} si l'appelant en est viewer.
     * Sans {@code spaceId} (ou sans droit) : globaux uniquement — aucun signal d'existence.
     */
    @Transactional(readOnly = true)
    public List<TemplateSummary> list(Jwt jwt, UUID spaceId) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        boolean includeSpace = spaceId != null
                && authorizationService.hasRelation(user.getId(), "space", spaceId, "viewer");

        List<Object> args = new ArrayList<>();
        String sql = "SELECT " + SELECT_COLUMNS + ACTIVE_JOIN;
        if (includeSpace) {
            sql += " AND (t.space_id IS NULL OR t.space_id = ?)";
            args.add(spaceId);
        } else {
            sql += " AND t.space_id IS NULL";
        }
        sql += " ORDER BY (t.space_id IS NULL) DESC, lower(t.name), t.id";

        List<TemplateRow> rows = jdbc.query(sql, rowMapper(false), args.toArray());
        ManageContext manage = new ManageContext(jwt, user.getId());
        return rows.stream().map(r -> toSummary(r, manage.canManage(r.spaceId()))).toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(Jwt jwt, UUID id) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        TemplateRow row = requireUsable(user.getId(), id, true);
        return toResponse(row, new ManageContext(jwt, user.getId()).canManage(row.spaceId()));
    }

    /**
     * Avertissements non bloquants avant création d'une page dans {@code spaceId} :
     * transclusions du modèle vers un espace en {@code external_reference=restricted}.
     */
    @Transactional(readOnly = true)
    public CreationWarningsResponse creationWarnings(Jwt jwt, UUID templateId, UUID spaceId) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        if (spaceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spaceId requis");
        }
        TemplateRow row = requireUsable(user.getId(), templateId, true);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");

        List<UUID> blocked = new ArrayList<>();
        for (UUID target : transclusionResolver.extractDirectTargets(row.body())) {
            UUID targetSpace = jdbc.query(
                    "SELECT space_id FROM documents WHERE id = ? AND deleted_at IS NULL",
                    rs -> rs.next() ? (UUID) rs.getObject("space_id") : null,
                    target);
            if (targetSpace != null && !transclusionResolver.allowsInterWorkspaceEdge(spaceId, targetSpace)) {
                blocked.add(target);
            }
        }
        List<CreationWarning> warnings = new ArrayList<>();
        if (!blocked.isEmpty()) {
            warnings.add(new CreationWarning(
                    WARNING_TRANSCLUSION_RESTRICTED,
                    blocked.size() + " transclusion(s) du modèle pointent vers un espace dont la "
                            + "référence externe est « restreinte » : elles seront masquées dans la page "
                            + "créée (la création n'est pas bloquée).",
                    List.copyOf(blocked)));
        }
        return new CreationWarningsResponse(templateId, spaceId, List.copyOf(warnings));
    }

    // ------------------------------------------------------------------
    // Écriture
    // ------------------------------------------------------------------

    @Transactional
    public TemplateResponse create(Jwt jwt, CreateTemplateRequest request) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        UUID spaceId = request.spaceId();
        requireManage(jwt, user.getId(), spaceId);
        if (spaceId != null) {
            requireActiveSpace(spaceId);
        }
        String name = requireName(request.name());
        Map<String, Object> body = requireBody(request.body());

        UUID id = insertTemplate(
                user.getId(), spaceId, name, cleanDescription(request.description()),
                blankToNull(request.docType()), normalizeTagIds(request.defaultTagIds()), body);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("scope", scopeOf(spaceId));
        if (spaceId != null) {
            meta.put("spaceId", spaceId.toString());
        }
        meta.put("version", 1);
        auditService.record(user.getId(), false, AuditActions.TEMPLATE_CREATED,
                TEMPLATE_RESOURCE, id, meta, null);
        return toResponse(loadRequired(id, false), true);
    }

    @Transactional
    public TemplateResponse update(Jwt jwt, UUID id, UpdateTemplateRequest request) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        TemplateRow row = requireUsable(user.getId(), id, true, true);
        requireManage(jwt, user.getId(), row.spaceId());

        List<String> changed = new ArrayList<>();
        String name = row.name();
        if (request.name() != null) {
            name = requireName(request.name());
            changed.add("name");
        }
        String description = row.description();
        if (request.description() != null) {
            description = cleanDescription(request.description());
            changed.add("description");
        }
        String docType = row.docType();
        if (request.docType() != null) {
            docType = blankToNull(request.docType());
            changed.add("doc_type");
        }
        List<UUID> tagIds = row.defaultTagIds();
        if (request.defaultTagIds() != null) {
            tagIds = normalizeTagIds(request.defaultTagIds());
            changed.add("default_tag_ids");
        }
        Map<String, Object> body = row.body();
        if (request.body() != null) {
            body = requireBody(request.body());
            changed.add("body");
        }

        final String fName = name;
        final String fDescription = description;
        final String fDocType = docType;
        final List<UUID> fTags = tagIds;
        final String bodyJson = toJson(body);
        jdbc.update("""
                UPDATE templates
                   SET name = ?, description = ?, doc_type = ?, default_tag_ids = ?,
                       body = ?::jsonb, version = version + 1, updated_by = ?, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """, ps -> {
            ps.setString(1, fName);
            ps.setString(2, fDescription);
            ps.setString(3, fDocType);
            ps.setArray(4, ps.getConnection().createArrayOf("uuid", fTags.toArray(new UUID[0])));
            ps.setString(5, bodyJson);
            ps.setObject(6, user.getId());
            ps.setObject(7, id);
        });

        TemplateRow updated = loadRequired(id, false);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", updated.name());
        meta.put("scope", scopeOf(row.spaceId()));
        meta.put("version", updated.version());
        meta.put("changed", changed);
        auditService.record(user.getId(), false, AuditActions.TEMPLATE_UPDATED,
                TEMPLATE_RESOURCE, id, meta, null);
        return toResponse(updated, true);
    }

    /** Soft-delete : les documents créés depuis ce modèle ne sont pas modifiés. */
    @Transactional
    public void delete(Jwt jwt, UUID id) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        TemplateRow row = requireUsable(user.getId(), id, false, true);
        requireManage(jwt, user.getId(), row.spaceId());

        jdbc.update("""
                UPDATE templates
                   SET deleted_at = now(), updated_by = ?, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """, user.getId(), id);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", row.name());
        meta.put("scope", scopeOf(row.spaceId()));
        meta.put("version", row.version());
        auditService.record(user.getId(), false, AuditActions.TEMPLATE_DELETED,
                TEMPLATE_RESOURCE, id, meta, null);
    }

    /**
     * « Enregistrer comme modèle » : exige {@code editor} sur le document et le droit de gérer
     * la portée cible. Copie le corps uniquement (pas de doc_type, tags ni métadonnées).
     */
    @Transactional
    public SaveAsTemplateResponse saveFromDocument(Jwt jwt, UUID documentId, SaveAsTemplateRequest request) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");

        String scope = request.scope() == null ? "" : request.scope().trim().toLowerCase(Locale.ROOT);
        UUID targetSpace;
        switch (scope) {
            case TemplateDtos.SCOPE_GLOBAL -> targetSpace = null;
            case TemplateDtos.SCOPE_SPACE -> {
                if (request.spaceId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "spaceId requis pour un modèle d'espace");
                }
                targetSpace = request.spaceId();
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope doit être global ou space");
        }
        requireManage(jwt, user.getId(), targetSpace);
        if (targetSpace != null) {
            requireActiveSpace(targetSpace);
        }
        String name = requireName(request.name());

        Map<String, Object> source = documentStore.readCurrentContent(doc.getId(), doc.getBody());
        Map<String, Object> body = transclusionResolver.normalizeForStorage(
                TemplateBodySupport.deepCopy(source));

        UUID id = insertTemplate(
                user.getId(), targetSpace, name, cleanDescription(request.description()),
                null, List.of(), body);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("document_id", documentId.toString());
        meta.put("name", name);
        meta.put("scope", scope);
        if (targetSpace != null) {
            meta.put("spaceId", targetSpace.toString());
        }
        auditService.record(user.getId(), false, AuditActions.TEMPLATE_CREATED_FROM_DOCUMENT,
                TEMPLATE_RESOURCE, id, meta, null);

        List<String> warnings = new ArrayList<>();
        String visibility = doc.getVisibility() == null ? DocumentVisibility.SPACE : doc.getVisibility();
        if (!DocumentVisibility.ORGANISATION.equals(visibility)) {
            warnings.add("Le document source n'est pas en visibilité « organisation » (« " + visibility
                    + " ») : son contenu sera visible de tous ceux qui peuvent utiliser ce modèle.");
        }
        return new SaveAsTemplateResponse(toResponse(loadRequired(id, false), true), List.copyOf(warnings));
    }

    // ------------------------------------------------------------------
    // Instanciation (appelé par DocumentService.create)
    // ------------------------------------------------------------------

    /**
     * Résultat de {@link #prepareInstantiation} : corps déjà substitué + métadonnées à appliquer.
     */
    public record TemplateInstantiation(
            UUID templateId,
            int version,
            String name,
            Map<String, Object> body,
            String docType,
            List<UUID> defaultTagIds
    ) {}

    /**
     * Charge le modèle (404 si absent / supprimé / illisible), vérifie le droit d'usage et
     * substitue {@code {{date}} {{auteur}} {{espace}} {{titre}}} dans les nœuds texte.
     */
    @Transactional(readOnly = true)
    public TemplateInstantiation prepareInstantiation(
            UserEntity user,
            UUID templateId,
            UUID spaceId,
            String title
    ) {
        TemplateRow row = requireUsable(user.getId(), templateId, true);
        if (row.spaceId() != null && !row.spaceId().equals(spaceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce modèle n'est pas utilisable dans cet espace");
        }
        Map<String, String> values = new HashMap<>();
        values.put(TemplateBodySupport.VAR_DATE, LocalDate.now(clock.withZone(PARIS)).toString());
        values.put(TemplateBodySupport.VAR_AUTEUR, user.getDisplayName() == null ? "" : user.getDisplayName());
        values.put(TemplateBodySupport.VAR_ESPACE, spaceName(spaceId));
        values.put(TemplateBodySupport.VAR_TITRE, title == null ? "" : title.trim());
        Map<String, Object> body = TemplateBodySupport.substituteVariables(row.body(), values);
        return new TemplateInstantiation(
                row.id(), row.version(), row.name(), body, row.docType(), row.defaultTagIds());
    }

    /**
     * Après insertion du document : tags par défaut (ids manquants ignorés) + audit
     * {@code template.used}. Ne modifie jamais le modèle.
     */
    public void afterDocumentCreated(UUID userId, TemplateInstantiation tpl, UUID documentId, UUID spaceId) {
        applyDefaultTags(documentId, tpl.defaultTagIds());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("document_id", documentId.toString());
        meta.put("spaceId", spaceId.toString());
        meta.put("templateVersion", tpl.version());
        meta.put("name", tpl.name());
        auditService.record(userId, false, AuditActions.TEMPLATE_USED,
                TEMPLATE_RESOURCE, tpl.templateId(), meta, null);
    }

    void applyDefaultTags(UUID documentId, List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                INSERT INTO document_tags (document_id, tag_id)
                SELECT ?, t.id FROM tags t WHERE t.id = ANY (?)
                ON CONFLICT DO NOTHING
                """, ps -> {
            ps.setObject(1, documentId);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", tagIds.toArray(new UUID[0])));
        });
    }

    // ------------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------------

    private boolean canUse(UUID userId, TemplateRow row) {
        return row.spaceId() == null
                || authorizationService.hasRelation(userId, "space", row.spaceId(), "viewer");
    }

    private TemplateRow requireUsable(UUID userId, UUID templateId, boolean withBody) {
        return requireUsable(userId, templateId, withBody, false);
    }

    private TemplateRow requireUsable(UUID userId, UUID templateId, boolean withBody, boolean forUpdate) {
        TemplateRow row = load(templateId, withBody || forUpdate, forUpdate);
        if (row == null || !canUse(userId, row)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modèle introuvable");
        }
        return row;
    }

    private TemplateRow loadRequired(UUID id, boolean forUpdate) {
        TemplateRow row = load(id, true, forUpdate);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modèle introuvable");
        }
        return row;
    }

    private TemplateRow load(UUID id, boolean withBody, boolean forUpdate) {
        String sql = "SELECT " + SELECT_COLUMNS + (withBody ? ", t.body::text AS body_json" : "")
                + ACTIVE_JOIN + " AND t.id = ?" + (forUpdate ? " FOR UPDATE OF t" : "");
        List<TemplateRow> rows = jdbc.query(sql, rowMapper(withBody), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Global → ADMINISTRATEUR_SYSTEME ; espace → owner de l'espace. */
    private void requireManage(Jwt jwt, UUID userId, UUID spaceId) {
        if (spaceId == null) {
            if (!identityFacade.isSystemAdmin(jwt)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Seuls les administrateurs système peuvent gérer les modèles globaux");
            }
            return;
        }
        if (!authorizationService.hasRelation(userId, "space", spaceId, "owner")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seuls les propriétaires de l'espace peuvent gérer ses modèles");
        }
    }

    private void requireActiveSpace(UUID spaceId) {
        Boolean exists = jdbc.query(
                "SELECT 1 FROM spaces WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE, spaceId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
    }

    private String spaceName(UUID spaceId) {
        if (spaceId == null) {
            return "";
        }
        String name = jdbc.query(
                "SELECT name FROM spaces WHERE id = ?",
                rs -> rs.next() ? rs.getString("name") : null,
                spaceId);
        return name == null ? "" : name;
    }

    private UUID insertTemplate(
            UUID userId,
            UUID spaceId,
            String name,
            String description,
            String docType,
            List<UUID> tagIds,
            Map<String, Object> body
    ) {
        UUID id = UUID.randomUUID();
        String bodyJson = toJson(body);
        jdbc.update("""
                INSERT INTO templates (
                  id, name, description, doc_type, default_tag_ids, body, space_id,
                  version, created_by, updated_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 1, ?, ?, now(), now())
                """, ps -> {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, docType);
            ps.setArray(5, ps.getConnection().createArrayOf("uuid", tagIds.toArray(new UUID[0])));
            ps.setString(6, bodyJson);
            if (spaceId == null) {
                ps.setNull(7, Types.OTHER);
            } else {
                ps.setObject(7, spaceId);
            }
            ps.setObject(8, userId);
            ps.setObject(9, userId);
        });
        return id;
    }

    private RowMapper<TemplateRow> rowMapper(boolean withBody) {
        return (rs, i) -> {
            Array tagArray = rs.getArray("default_tag_ids");
            List<UUID> tags = new ArrayList<>();
            if (tagArray != null) {
                for (Object o : (Object[]) tagArray.getArray()) {
                    tags.add(o instanceof UUID u ? u : UUID.fromString(String.valueOf(o)));
                }
            }
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");
            return new TemplateRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("doc_type"),
                    (UUID) rs.getObject("space_id"),
                    List.copyOf(tags),
                    rs.getInt("version"),
                    (UUID) rs.getObject("created_by"),
                    (UUID) rs.getObject("updated_by"),
                    created == null ? null : created.toInstant(),
                    updated == null ? null : updated.toInstant(),
                    withBody ? fromJson(rs.getString("body_json")) : null);
        };
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corps TipTap invalide");
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps de modèle illisible en base", e);
        }
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le nom du modèle est requis");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le nom du modèle est limité à " + MAX_NAME_LENGTH + " caractères");
        }
        return name;
    }

    private static String cleanDescription(String raw) {
        String d = blankToNull(raw);
        if (d != null && d.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La description est limitée à " + MAX_DESCRIPTION_LENGTH + " caractères");
        }
        return d;
    }

    private Map<String, Object> requireBody(Map<String, Object> body) {
        if (body == null || !"doc".equals(String.valueOf(body.get("type")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Corps TipTap requis (type « doc »)");
        }
        return transclusionResolver.normalizeForStorage(TemplateBodySupport.deepCopy(body));
    }

    private static List<UUID> normalizeTagIds(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String scopeOf(UUID spaceId) {
        return spaceId == null ? TemplateDtos.SCOPE_GLOBAL : TemplateDtos.SCOPE_SPACE;
    }

    private static TemplateSummary toSummary(TemplateRow r, boolean canManage) {
        return new TemplateSummary(
                r.id(), r.name(), r.description(), r.docType(), scopeOf(r.spaceId()), r.spaceId(),
                r.defaultTagIds(), r.version(), r.createdBy(), r.updatedBy(),
                r.createdAt(), r.updatedAt(), canManage);
    }

    private static TemplateResponse toResponse(TemplateRow r, boolean canManage) {
        return new TemplateResponse(
                r.id(), r.name(), r.description(), r.docType(), scopeOf(r.spaceId()), r.spaceId(),
                r.defaultTagIds(), r.version(), r.createdBy(), r.updatedBy(),
                r.createdAt(), r.updatedAt(), canManage, r.body());
    }

    private record TemplateRow(
            UUID id,
            String name,
            String description,
            String docType,
            UUID spaceId,
            List<UUID> defaultTagIds,
            int version,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> body
    ) {}

    /** Calcul paresseux / mémoïsé du droit de gestion (un appel admin, un check par espace). */
    private final class ManageContext {
        private final Jwt jwt;
        private final UUID userId;
        private Boolean admin;
        private final Map<UUID, Boolean> owners = new HashMap<>();

        ManageContext(Jwt jwt, UUID userId) {
            this.jwt = jwt;
            this.userId = userId;
        }

        boolean canManage(UUID spaceId) {
            if (spaceId == null) {
                if (admin == null) {
                    admin = identityFacade.isSystemAdmin(jwt);
                }
                return admin;
            }
            return owners.computeIfAbsent(spaceId,
                    id -> authorizationService.hasRelation(userId, "space", id, "owner"));
        }
    }
}
