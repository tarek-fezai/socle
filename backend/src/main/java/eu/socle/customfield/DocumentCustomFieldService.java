// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.customfield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Champs personnalisés d'un document : définitions applicables (actives, portée
 * {@code all_spaces} ou l'espace du document) + valeurs. Lecture : {@code viewer} ;
 * écriture : {@code editor}. La valeur est validée selon {@code field_type}.
 */
@Service
public class DocumentCustomFieldService {

    public static final int MAX_TEXT_LENGTH = 2000;
    public static final String SCOPE_ALL_SPACES = "all_spaces";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public DocumentCustomFieldService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    /** @param value valeur courante ({@code null} si non renseignée) */
    public record CustomFieldView(
            UUID id,
            String name,
            String slug,
            String fieldType,
            boolean required,
            JsonNode options,
            JsonNode value
    ) {}

    private record Definition(
            UUID id, String name, String slug, String fieldType, boolean required, JsonNode options
    ) {}

    @Transactional(readOnly = true)
    public List<CustomFieldView> list(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        UUID spaceId = requireDocumentSpace(documentId);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");

        Map<UUID, JsonNode> values = new LinkedHashMap<>();
        jdbc.query("SELECT field_id, value::text AS value FROM document_custom_field_values WHERE document_id = ?",
                (rs, i) -> {
                    values.put((UUID) rs.getObject("field_id"), parse(rs.getString("value")));
                    return null;
                },
                documentId);

        List<CustomFieldView> out = new ArrayList<>();
        for (Definition d : applicableDefinitions(spaceId, null)) {
            out.add(new CustomFieldView(
                    d.id(), d.name(), d.slug(), d.fieldType(), d.required(), d.options(),
                    values.get(d.id())));
        }
        return List.copyOf(out);
    }

    /**
     * Pose la valeur (ou l'efface si {@code value} est null / chaîne vide — refusé si le champ est
     * obligatoire). Retourne la vue à jour du champ.
     */
    @Transactional
    public CustomFieldView setValue(Jwt jwt, UUID documentId, UUID fieldId, JsonNode value) {
        var user = userSyncService.syncFromJwt(jwt);
        UUID spaceId = requireDocumentSpace(documentId);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");

        Definition def = applicableDefinitions(spaceId, fieldId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Champ personnalisé introuvable"));

        JsonNode normalized = validate(def, value);
        if (normalized == null) {
            if (def.required()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le champ « " + def.name() + " » est obligatoire");
            }
            jdbc.update("DELETE FROM document_custom_field_values WHERE document_id = ? AND field_id = ?",
                    documentId, fieldId);
        } else {
            jdbc.update("""
                    INSERT INTO document_custom_field_values (document_id, field_id, value)
                    VALUES (?, ?, ?::jsonb)
                    ON CONFLICT (document_id, field_id) DO UPDATE SET value = EXCLUDED.value
                    """, documentId, fieldId, normalized.toString());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fieldId", fieldId.toString());
        meta.put("slug", def.slug());
        meta.put("cleared", normalized == null);
        auditService.record(
                user.getId(), false, AuditActions.DOCUMENT_CUSTOM_FIELD_UPDATED,
                "document", documentId, meta, null);

        return new CustomFieldView(
                def.id(), def.name(), def.slug(), def.fieldType(), def.required(), def.options(), normalized);
    }

    /** Document actif → espace ; sinon 404. */
    private UUID requireDocumentSpace(UUID documentId) {
        List<UUID> rows = jdbc.query(
                "SELECT space_id FROM documents WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> (UUID) rs.getObject("space_id"), documentId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        return rows.getFirst();
    }

    private List<Definition> applicableDefinitions(UUID spaceId, UUID onlyFieldId) {
        return jdbc.query("""
                SELECT id, name, slug, field_type, is_required, options::text AS options
                  FROM custom_field_definitions
                 WHERE status = 'active'
                   AND (scope = ? OR scope = ?)
                   AND (?::uuid IS NULL OR id = ?::uuid)
                 ORDER BY name, id
                """,
                (rs, i) -> new Definition(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("field_type"),
                        rs.getBoolean("is_required"),
                        parse(rs.getString("options"))),
                SCOPE_ALL_SPACES, spaceId.toString(), onlyFieldId, onlyFieldId);
    }

    /** @return valeur normalisée, ou {@code null} pour « effacer » */
    JsonNode validate(Definition def, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String type = def.fieldType();
        switch (type) {
            case "texte" -> {
                String s = requireText(value, def);
                String t = s.trim();
                if (t.isEmpty()) {
                    return null;
                }
                if (t.length() > MAX_TEXT_LENGTH) {
                    throw bad(def, "dépasse " + MAX_TEXT_LENGTH + " caractères");
                }
                return objectMapper.getNodeFactory().textNode(t);
            }
            case "nombre" -> {
                if (!value.isNumber()) {
                    throw bad(def, "doit être un nombre");
                }
                return value;
            }
            case "case_a_cocher" -> {
                if (!value.isBoolean()) {
                    throw bad(def, "doit être un booléen");
                }
                return value;
            }
            case "date" -> {
                String s = requireText(value, def).trim();
                if (s.isEmpty()) {
                    return null;
                }
                try {
                    LocalDate.parse(s);
                } catch (DateTimeParseException e) {
                    throw bad(def, "doit être une date AAAA-MM-JJ");
                }
                return objectMapper.getNodeFactory().textNode(s);
            }
            case "lien" -> {
                String s = requireText(value, def).trim();
                if (s.isEmpty()) {
                    return null;
                }
                if (!(s.startsWith("https://") || s.startsWith("http://")) || s.length() > MAX_TEXT_LENGTH) {
                    throw bad(def, "doit être une URL http(s)");
                }
                return objectMapper.getNodeFactory().textNode(s);
            }
            case "personne" -> {
                String s = requireText(value, def).trim();
                if (s.isEmpty()) {
                    return null;
                }
                try {
                    UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    throw bad(def, "doit être un identifiant de personne");
                }
                return objectMapper.getNodeFactory().textNode(s);
            }
            case "liste" -> {
                String s = requireText(value, def).trim();
                if (s.isEmpty()) {
                    return null;
                }
                if (!allowedOptions(def).contains(s)) {
                    throw bad(def, "valeur hors des options autorisées");
                }
                return objectMapper.getNodeFactory().textNode(s);
            }
            case "multi_selection" -> {
                if (!value.isArray()) {
                    throw bad(def, "doit être une liste de valeurs");
                }
                Set<String> allowed = allowedOptions(def);
                var arr = objectMapper.createArrayNode();
                Set<String> seen = new HashSet<>();
                for (JsonNode item : value) {
                    if (!item.isTextual() || !allowed.contains(item.asText())) {
                        throw bad(def, "valeur hors des options autorisées");
                    }
                    if (seen.add(item.asText())) {
                        arr.add(item.asText());
                    }
                }
                return arr.isEmpty() ? null : arr;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Type de champ non pris en charge : " + type);
        }
    }

    private static String requireText(JsonNode value, Definition def) {
        if (!value.isTextual()) {
            throw bad(def, "doit être du texte");
        }
        return value.asText();
    }

    private static Set<String> allowedOptions(Definition def) {
        Set<String> out = new HashSet<>();
        JsonNode options = def.options();
        if (options != null && options.isArray()) {
            for (JsonNode o : options) {
                if (o.isTextual()) {
                    out.add(o.asText());
                } else if (o.isObject()) {
                    JsonNode v = o.has("value") ? o.get("value") : o.get("label");
                    if (v != null && v.isTextual()) {
                        out.add(v.asText());
                    }
                }
            }
        }
        return out;
    }

    private static ResponseStatusException bad(Definition def, String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valeur invalide pour « " + def.name() + " » : " + reason);
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON invalide en base (champ personnalisé)", e);
        }
    }
}
