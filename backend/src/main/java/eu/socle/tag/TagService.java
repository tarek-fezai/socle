// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.TagRef;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Étiquettes : recherche (autocomplétion) et rattachement / détachement sur un document.
 * Les écritures exigent {@code editor} sur le document et sont auditées.
 */
@Service
public class TagService {

    public static final int MAX_NAME_LENGTH = 50;
    public static final int DEFAULT_SEARCH_LIMIT = 20;
    public static final int MAX_SEARCH_LIMIT = 50;

    private static final RowMapper<TagRef> TAG_MAPPER = (rs, i) -> new TagRef(
            (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("color"));

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public TagService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    /** @param created true si l'étiquette vient d'être rattachée (false = déjà présente, idempotent) */
    public record Attachment(TagRef tag, boolean created) {}

    /** Recherche insensible à la casse (sous-chaîne), préfixes d'abord ; {@code q} vide = tout. */
    @Transactional(readOnly = true)
    public List<TagRef> search(Jwt jwt, String q, Integer limit) {
        userSyncService.syncFromJwt(jwt);
        int lim = limit == null ? DEFAULT_SEARCH_LIMIT : Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        String term = q == null ? "" : q.trim();
        String escaped = escapeLike(term);
        return jdbc.query("""
                SELECT id, name, color
                  FROM tags
                 WHERE name ILIKE ? ESCAPE '\\'
                 ORDER BY CASE WHEN name ILIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, lower(name), id
                 LIMIT ?
                """,
                TAG_MAPPER, "%" + escaped + "%", escaped + "%", lim);
    }

    /**
     * Rattache une étiquette existante ({@code tagId}) ou par nom (créée si nouvelle, insensible
     * à la casse). Exactement un des deux paramètres.
     */
    @Transactional
    public Attachment attach(Jwt jwt, UUID documentId, UUID tagId, String name) {
        boolean hasName = name != null && !name.isBlank();
        if ((tagId == null) == !hasName) {
            // les deux ou aucun
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Indiquer exactement un des champs tagId ou name");
        }
        var user = userSyncService.syncFromJwt(jwt);
        requireEditableDocument(user.getId(), documentId);

        boolean tagCreated = false;
        TagRef tag;
        if (tagId != null) {
            tag = findById(tagId);
            if (tag == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Étiquette introuvable");
            }
        } else {
            String clean = normalizeName(name);
            tag = findByName(clean);
            if (tag == null) {
                tagCreated = jdbc.update(
                        "INSERT INTO tags (name) VALUES (?) ON CONFLICT (name) DO NOTHING", clean) > 0;
                tag = findByName(clean);
                if (tag == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Étiquette en conflit, réessayer");
                }
            }
        }

        int inserted = jdbc.update("""
                INSERT INTO document_tags (document_id, tag_id) VALUES (?, ?)
                ON CONFLICT (document_id, tag_id) DO NOTHING
                """, documentId, tag.id());
        if (inserted > 0) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tagId", tag.id().toString());
            meta.put("name", tag.name());
            meta.put("tagCreated", tagCreated);
            auditService.record(
                    user.getId(), false, AuditActions.DOCUMENT_TAG_ADDED,
                    "document", documentId, meta, null);
        }
        return new Attachment(tag, inserted > 0);
    }

    /** Détache l'étiquette du document ; 404 si elle n'y était pas rattachée. */
    @Transactional
    public void detach(Jwt jwt, UUID documentId, UUID tagId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireEditableDocument(user.getId(), documentId);

        TagRef tag = findById(tagId);
        int deleted = jdbc.update(
                "DELETE FROM document_tags WHERE document_id = ? AND tag_id = ?", documentId, tagId);
        if (tag == null || deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Étiquette non rattachée au document");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tagId", tag.id().toString());
        meta.put("name", tag.name());
        auditService.record(
                user.getId(), false, AuditActions.DOCUMENT_TAG_REMOVED,
                "document", documentId, meta, null);
    }

    /** 404 si le document n'existe pas (ou est en corbeille), puis 403 sans droit editor. */
    private void requireEditableDocument(UUID userId, UUID documentId) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ? AND deleted_at IS NULL",
                Integer.class, documentId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        authorizationService.requireDocumentRelation(userId, documentId, "editor");
    }

    private TagRef findById(UUID tagId) {
        return jdbc.query("SELECT id, name, color FROM tags WHERE id = ?", TAG_MAPPER, tagId)
                .stream().findFirst().orElse(null);
    }

    private TagRef findByName(String name) {
        return jdbc.query(
                        "SELECT id, name, color FROM tags WHERE lower(name) = lower(?) ORDER BY name LIMIT 1",
                        TAG_MAPPER, name)
                .stream().findFirst().orElse(null);
    }

    static String normalizeName(String raw) {
        String clean = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom d'étiquette requis");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nom d'étiquette trop long (" + MAX_NAME_LENGTH + " caractères max)");
        }
        return clean;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
