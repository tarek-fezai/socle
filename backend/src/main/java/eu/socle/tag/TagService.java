// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.TagRef;
import eu.socle.identity.IdentityFacade;
import eu.socle.user.UserSyncService;
import eu.socle.web.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
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

    /**
     * Étiquette gouvernée = référencée par ≥ 1 attribution de rôle d'approbation de portée
     * {@code tag} ({@code scope_ref} = UUID de l'étiquette en texte, cf. V23).
     */
    private static final String GOVERNED_EXPR = """
            EXISTS (SELECT 1 FROM approval_role_assignments ara
                     WHERE ara.scope_type = 'tag' AND lower(ara.scope_ref) = t.id::text)
            """;

    private static final String SELECT_TAG = "SELECT t.id, t.name, t.color, " + GOVERNED_EXPR
            + " AS governed FROM tags t ";

    private static final RowMapper<TagRef> TAG_MAPPER = (rs, i) -> new TagRef(
            (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("color"),
            rs.getBoolean("governed"));

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final IdentityFacade identityFacade;

    public TagService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            IdentityFacade identityFacade
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.identityFacade = identityFacade;
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
        return jdbc.query(SELECT_TAG + """
                 WHERE t.name ILIKE ? ESCAPE '\\'
                 ORDER BY CASE WHEN t.name ILIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, lower(t.name), t.id
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
                if (isTagCreationRestricted() && !identityFacade.isSystemAdmin(jwt)) {
                    throw ApiErrors.tagCreationRestricted();
                }
                tagCreated = jdbc.update("""
                        INSERT INTO tags (name, created_by, created_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (name) DO NOTHING
                        """, clean, user.getId(), Timestamp.from(Instant.now())) > 0;
                tag = findByName(clean);
                if (tag == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Étiquette en conflit, réessayer");
                }
            }
        }
        // Étiquette gouvernée : owner uniquement (403) et pas pendant une approbation (409).
        // Une étiquette tout juste créée n'est jamais gouvernée ; en cas de rejet, la transaction
        // est annulée (aucune étiquette orpheline).
        requireGovernedTagChangeAllowed(user.getId(), documentId, tag);

        int inserted = jdbc.update("""
                INSERT INTO document_tags (document_id, tag_id) VALUES (?, ?)
                ON CONFLICT (document_id, tag_id) DO NOTHING
                """, documentId, tag.id());
        if (inserted > 0) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tagId", tag.id().toString());
            meta.put("name", tag.name());
            meta.put("tagCreated", tagCreated);
            if (tag.governed()) {
                meta.put("governed", true);
            }
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
        if (tag != null) {
            requireGovernedTagChangeAllowed(user.getId(), documentId, tag);
        }
        int deleted = jdbc.update(
                "DELETE FROM document_tags WHERE document_id = ? AND tag_id = ?", documentId, tagId);
        if (tag == null || deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Étiquette non rattachée au document");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tagId", tag.id().toString());
        meta.put("name", tag.name());
        if (tag.governed()) {
            meta.put("governed", true);
        }
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

    /**
     * Étiquette gouvernée : rattachement / détachement réservés aux owners (document ou espace),
     * 403 sinon ; puis 409 si une demande d'approbation est en cours sur le document (le périmètre
     * des approbateurs dépend des étiquettes — on ne le change pas en cours de route).
     * L'ordre 403 → 409 évite de révéler l'état d'approbation à un non-owner.
     * Sans effet pour une étiquette libre (editor suffit).
     */
    private void requireGovernedTagChangeAllowed(UUID userId, UUID documentId, TagRef tag) {
        if (!tag.governed()) {
            return;
        }
        if (!isOwner(userId, documentId)) {
            throw ApiErrors.governedTagOwnerOnly();
        }
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE document_id = ? AND status = 'en_cours'",
                Integer.class, documentId);
        if (pending != null && pending > 0) {
            throw ApiErrors.approvalInProgress();
        }
    }

    /** Owner du document, ou de l'espace qui le contient (cf. {@code canManageAccess}). */
    private boolean isOwner(UUID userId, UUID documentId) {
        if (authorizationService.hasRelation(userId, "document", documentId, "owner")) {
            return true;
        }
        UUID spaceId = jdbc.query("SELECT space_id FROM documents WHERE id = ?",
                        (rs, i) -> (UUID) rs.getObject("space_id"), documentId)
                .stream().findFirst().orElse(null);
        return spaceId != null && authorizationService.hasRelation(userId, "space", spaceId, "owner");
    }

    private TagRef findById(UUID tagId) {
        return jdbc.query(SELECT_TAG + "WHERE t.id = ?", TAG_MAPPER, tagId)
                .stream().findFirst().orElse(null);
    }

    private TagRef findByName(String name) {
        return jdbc.query(
                        SELECT_TAG + "WHERE lower(t.name) = lower(?) ORDER BY t.name LIMIT 1",
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

    private boolean isTagCreationRestricted() {
        List<String> rows = jdbc.query(
                "SELECT tag_creation_policy FROM instance_settings WHERE id = true",
                (rs, i) -> rs.getString(1));
        if (rows.isEmpty()) {
            return false;
        }
        return TagAdminService.POLICY_ADMINS_ONLY.equals(rows.getFirst());
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
