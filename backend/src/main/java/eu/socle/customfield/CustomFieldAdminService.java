// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.customfield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.customfield.CustomFieldAdminDtos.CreateFieldRequest;
import eu.socle.customfield.CustomFieldAdminDtos.FieldAdminView;
import eu.socle.customfield.CustomFieldAdminDtos.FieldListResponse;
import eu.socle.customfield.CustomFieldAdminDtos.UpdateFieldRequest;
import eu.socle.identity.IdentityFacade;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Administration des définitions de champs personnalisés (CustomFields).
 * Accès via {@code /api/v1/admin/**} (ADMINISTRATEUR_SYSTEME).
 */
@Service
public class CustomFieldAdminService {

    public static final Set<String> FIELD_TYPES = Set.of(
            "texte", "liste", "multi_selection", "date", "nombre", "lien", "personne", "case_a_cocher");
    public static final String SCOPE_ALL = DocumentCustomFieldService.SCOPE_ALL_SPACES;
    public static final int MAX_ACTIVE = 20;
    public static final int MAX_LIST_VALUES = 50;

    private static final Pattern SLUG_SAFE = Pattern.compile("[^a-z0-9]+");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;

    private final RowMapper<FieldAdminView> mapper = (rs, i) -> new FieldAdminView(
            (UUID) rs.getObject("id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("help_text"),
            rs.getString("field_type"),
            rs.getString("scope"),
            rs.getString("scope_space_name"),
            rs.getBoolean("is_required"),
            parse(rs.getString("options")),
            rs.getString("status"),
            rs.getLong("document_count"),
            rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toInstant()
                    : null);

    public CustomFieldAdminService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdentityFacade identityFacade,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public FieldListResponse list(Jwt jwt) {
        requireAdmin(jwt);
        List<FieldAdminView> fields = jdbc.query("""
                SELECT c.id, c.name, c.slug, c.help_text, c.field_type, c.scope, c.is_required,
                       c.options::text AS options, c.status, c.created_at,
                       s.name AS scope_space_name,
                       (SELECT count(*) FROM document_custom_field_values v WHERE v.field_id = c.id)
                         AS document_count
                  FROM custom_field_definitions c
                  LEFT JOIN spaces s ON c.scope <> 'all_spaces'
                    AND s.id::text = c.scope AND s.deleted_at IS NULL
                 ORDER BY lower(c.name), c.id
                """, mapper);
        return new FieldListResponse(fields);
    }

    @Transactional
    public FieldAdminView create(Jwt jwt, CreateFieldRequest request) {
        UserEntity admin = requireAdmin(jwt);
        String name = requireName(request.name());
        String type = requireType(request.fieldType());
        String scope = requireScope(request.scope());
        String status = normalizeStatus(request.status(), "draft");
        if ("active".equals(status)) {
            enforceActiveCap(null);
        }
        JsonNode options = normalizeOptions(type, request.options(), false);
        String slug = uniqueSlug(slugify(name));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO custom_field_definitions
                  (id, name, slug, help_text, field_type, scope, is_required, options, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                id, name, slug, blankToNull(request.helpText()), type, scope,
                request.required(), options == null ? null : options.toString(),
                status, Timestamp.from(now));
        auditService.record(admin.getId(), false, AuditActions.CUSTOM_FIELD_CREATED,
                "custom_field_definition", id,
                Map.of("name", name, "slug", slug, "fieldType", type, "status", status), null);
        return requireView(id);
    }

    @Transactional
    public FieldAdminView update(Jwt jwt, UUID fieldId, UpdateFieldRequest request) {
        UserEntity admin = requireAdmin(jwt);
        FieldAdminView current = requireView(fieldId);
        String name = requireName(request.name());
        String type = request.fieldType() == null || request.fieldType().isBlank()
                ? current.fieldType()
                : requireType(request.fieldType());
        if (!type.equals(current.fieldType()) && current.documentCount() > 0) {
            throw ApiErrors.fieldTypeLocked();
        }
        String scope = request.scope() == null || request.scope().isBlank()
                ? current.scope()
                : requireScope(request.scope());
        boolean required = request.required() != null ? request.required() : current.required();
        String status = request.status() == null || request.status().isBlank()
                ? current.status()
                : normalizeStatus(request.status(), current.status());
        if ("active".equals(status) && !"active".equals(current.status())) {
            enforceActiveCap(fieldId);
        }

        JsonNode nextOptions = request.options() != null
                ? mergeOptions(current, type, request.options(), request.archiveRemovedOptions())
                : current.options();

        jdbc.update("""
                UPDATE custom_field_definitions
                   SET name = ?, help_text = ?, field_type = ?, scope = ?, is_required = ?,
                       options = ?::jsonb, status = ?
                 WHERE id = ?
                """,
                name, blankToNull(request.helpText()), type, scope, required,
                nextOptions == null ? null : nextOptions.toString(), status, fieldId);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("status", status);
        meta.put("fieldType", type);
        String action = "archived".equals(status) && !"archived".equals(current.status())
                ? AuditActions.CUSTOM_FIELD_ARCHIVED
                : AuditActions.CUSTOM_FIELD_UPDATED;
        auditService.record(admin.getId(), false, action, "custom_field_definition", fieldId, meta, null);
        return requireView(fieldId);
    }

    /**
     * Suppression : si utilisé → archivage (valeurs conservées) ; sinon suppression réelle.
     */
    @Transactional
    public FieldAdminView deleteOrArchive(Jwt jwt, UUID fieldId) {
        UserEntity admin = requireAdmin(jwt);
        FieldAdminView current = requireView(fieldId);
        if (current.documentCount() > 0) {
            jdbc.update("UPDATE custom_field_definitions SET status = 'archived' WHERE id = ?", fieldId);
            auditService.record(admin.getId(), false, AuditActions.CUSTOM_FIELD_ARCHIVED,
                    "custom_field_definition", fieldId,
                    Map.of("name", current.name(), "documentCount", current.documentCount()), null);
            return requireView(fieldId);
        }
        jdbc.update("DELETE FROM custom_field_definitions WHERE id = ?", fieldId);
        auditService.record(admin.getId(), false, AuditActions.CUSTOM_FIELD_ARCHIVED,
                "custom_field_definition", fieldId,
                Map.of("name", current.name(), "deleted", true), null);
        return current;
    }

    private JsonNode mergeOptions(
            FieldAdminView current,
            String type,
            JsonNode requested,
            boolean archiveRemoved
    ) {
        if (!"liste".equals(type) && !"multi_selection".equals(type)) {
            return null;
        }
        List<Option> currentOpts = parseOptionsList(current.options());
        List<Option> requestedOpts = parseOptionsList(requested);
        Set<String> requestedValues = new LinkedHashSet<>();
        for (Option o : requestedOpts) {
            requestedValues.add(o.value());
        }
        List<Option> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Option o : requestedOpts) {
            if (seen.add(o.value())) {
                // Conserver archived si déjà archivée et toujours présente
                Option prev = currentOpts.stream()
                        .filter(c -> c.value().equals(o.value())).findFirst().orElse(null);
                boolean archived = o.archived() || (prev != null && prev.archived());
                result.add(new Option(o.value(), o.label(), archived));
            }
        }
        for (Option prev : currentOpts) {
            if (requestedValues.contains(prev.value())) {
                continue;
            }
            long usage = countOptionUsage(current.id(), type, prev.value());
            if (usage > 0) {
                if (!archiveRemoved) {
                    throw ApiErrors.fieldOptionInUse(prev.value(), usage);
                }
                result.add(new Option(prev.value(), prev.label(), true));
            }
            // sinon : retrait silencieux (jamais utilisé)
        }
        if (result.stream().filter(o -> !o.archived()).count() > MAX_LIST_VALUES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maximum " + MAX_LIST_VALUES + " valeurs de liste actives");
        }
        return toOptionsJson(result);
    }

    private long countOptionUsage(UUID fieldId, String type, String option) {
        if ("multi_selection".equals(type)) {
            Long n = jdbc.queryForObject("""
                    SELECT count(*) FROM document_custom_field_values
                     WHERE field_id = ?
                       AND value @> ?::jsonb
                    """, Long.class, fieldId, "[\"" + option.replace("\"", "\\\"") + "\"]");
            return n == null ? 0 : n;
        }
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM document_custom_field_values
                 WHERE field_id = ?
                   AND value = to_jsonb(?::text)
                """, Long.class, fieldId, option);
        return n == null ? 0 : n;
    }

    private JsonNode normalizeOptions(String type, JsonNode options, boolean allowArchived) {
        if (!"liste".equals(type) && !"multi_selection".equals(type)) {
            return null;
        }
        List<Option> list = parseOptionsList(options);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Au moins une valeur de liste est requise");
        }
        long active = list.stream().filter(o -> !o.archived()).count();
        if (active == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Au moins une valeur de liste active est requise");
        }
        if (active > MAX_LIST_VALUES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maximum " + MAX_LIST_VALUES + " valeurs de liste actives");
        }
        if (!allowArchived) {
            list = list.stream().map(o -> new Option(o.value(), o.label(), false)).toList();
        }
        return toOptionsJson(list);
    }

    private List<Option> parseOptionsList(JsonNode options) {
        List<Option> out = new ArrayList<>();
        if (options == null || options.isNull()) {
            return out;
        }
        JsonNode arr = options;
        if (options.isObject() && options.has("choices")) {
            arr = options.get("choices");
        }
        if (!arr.isArray()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode o : arr) {
            String value;
            String label;
            boolean archived = false;
            if (o.isTextual()) {
                value = o.asText().trim();
                label = value;
            } else if (o.isObject()) {
                JsonNode v = o.has("value") ? o.get("value") : o.get("label");
                if (v == null || !v.isTextual()) {
                    continue;
                }
                value = v.asText().trim();
                label = o.has("label") && o.get("label").isTextual()
                        ? o.get("label").asText().trim() : value;
                archived = o.has("archived") && o.get("archived").asBoolean(false);
            } else {
                continue;
            }
            if (value.isEmpty() || !seen.add(value)) {
                continue;
            }
            out.add(new Option(value, label, archived));
        }
        return out;
    }

    private JsonNode toOptionsJson(List<Option> options) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Option o : options) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("value", o.value());
            n.put("label", o.label());
            if (o.archived()) {
                n.put("archived", true);
            }
            arr.add(n);
        }
        return arr;
    }

    private void enforceActiveCap(UUID exceptId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM custom_field_definitions
                 WHERE status = 'active' AND (?::uuid IS NULL OR id <> ?::uuid)
                """, Integer.class, exceptId, exceptId);
        if (n != null && n >= MAX_ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximum " + MAX_ACTIVE + " champs actifs");
        }
    }

    private String uniqueSlug(String base) {
        String slug = base;
        int i = 2;
        while (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM custom_field_definitions WHERE slug = ?)",
                Boolean.class, slug))) {
            slug = base + "_" + i++;
        }
        return slug;
    }

    static String slugify(String name) {
        String n = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        n = SLUG_SAFE.matcher(n).replaceAll("_");
        n = n.replaceAll("^_+|_+$", "");
        if (n.isEmpty()) {
            n = "champ";
        }
        if (n.length() > 80) {
            n = n.substring(0, 80);
        }
        return n;
    }

    private FieldAdminView requireView(UUID id) {
        List<FieldAdminView> rows = jdbc.query("""
                SELECT c.id, c.name, c.slug, c.help_text, c.field_type, c.scope, c.is_required,
                       c.options::text AS options, c.status, c.created_at,
                       s.name AS scope_space_name,
                       (SELECT count(*) FROM document_custom_field_values v WHERE v.field_id = c.id)
                         AS document_count
                  FROM custom_field_definitions c
                  LEFT JOIN spaces s ON c.scope <> 'all_spaces'
                    AND s.id::text = c.scope AND s.deleted_at IS NULL
                 WHERE c.id = ?
                """, mapper, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Champ introuvable");
        }
        return rows.getFirst();
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom requis");
        }
        return n;
    }

    private static String requireType(String type) {
        if (type == null || !FIELD_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de champ invalide");
        }
        return type;
    }

    private String requireScope(String scope) {
        if (SCOPE_ALL.equals(scope)) {
            return SCOPE_ALL;
        }
        try {
            UUID spaceId = UUID.fromString(scope);
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM spaces WHERE id = ? AND deleted_at IS NULL",
                    Integer.class, spaceId);
            if (n == null || n == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Espace introuvable");
            }
            return spaceId.toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Portée invalide (all_spaces ou UUID d'espace)");
        }
    }

    private static String normalizeStatus(String status, String fallback) {
        String s = status == null || status.isBlank() ? fallback : status.trim();
        if (!Set.of("draft", "active", "archived").contains(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut invalide");
        }
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private record Option(String value, String label, boolean archived) {}
}
