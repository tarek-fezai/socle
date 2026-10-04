// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brouillons d'édition (autosave) — un par (document, utilisateur), uniquement en PostgreSQL.
 *
 * <p><b>Contrat</b> : ce service ne dépend volontairement ni de {@code AuditService}, ni de
 * {@code DocumentStore} (Git / relational), ni du publisher d'événements : écrire un brouillon ne
 * crée jamais de version, d'entrée d'audit, d'événement d'activité, d'outbox/webhook ni de commit
 * Git. Seule la sauvegarde explicite ({@link DocumentService#update}) crée une version, puis
 * supprime le brouillon de l'auteur.
 *
 * <p>Un brouillon n'est visible que par son auteur : toutes les requêtes sont filtrées sur
 * {@code user_id} ; le brouillon d'un autre utilisateur est indistinguable d'une absence (404).
 */
@Service
public class DocumentDraftService {

    private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final EditLockService editLockService;
    private final TipTapContentValidator tipTapContentValidator;

    @Autowired
    public DocumentDraftService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            EditLockService editLockService,
            TipTapContentValidator tipTapContentValidator
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.editLockService = editLockService;
        this.tipTapContentValidator = tipTapContentValidator != null
                ? tipTapContentValidator
                : new TipTapContentValidator();
    }

    /** Constructeur tests / compat — validateur par défaut. */
    public DocumentDraftService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            EditLockService editLockService
    ) {
        this(jdbc, objectMapper, userSyncService, authorizationService, editLockService,
                new TipTapContentValidator());
    }

    public record DraftView(String title, Map<String, Object> body, int baseVersionNo, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public DraftView get(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireEditableDocument(user.getId(), documentId);
        return find(documentId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillon introuvable"));
    }

    /** Upsert du brouillon de l'appelant ; exige le verrou d'édition (409 sinon). */
    @Transactional
    public DraftView put(Jwt jwt, UUID documentId, String title, Map<String, Object> body, Integer baseVersionNo) {
        var user = userSyncService.syncFromJwt(jwt);
        requireEditableDocument(user.getId(), documentId);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body requis");
        }
        tipTapContentValidator.validate(body);
        if (baseVersionNo == null || baseVersionNo < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseVersionNo requis");
        }
        if (!editLockService.isHeldBy(documentId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verrou d'édition requis : ouvrez le document en modification pour enregistrer un brouillon");
        }
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body non sérialisable");
        }
        jdbc.update("""
                INSERT INTO document_drafts (document_id, user_id, body, title, base_version_no, updated_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, now())
                ON CONFLICT (document_id, user_id) DO UPDATE SET
                    body = EXCLUDED.body,
                    title = EXCLUDED.title,
                    base_version_no = EXCLUDED.base_version_no,
                    updated_at = EXCLUDED.updated_at
                """,
                documentId, user.getId(), bodyJson, title, baseVersionNo);
        return find(documentId, user.getId()).orElseThrow();
    }

    /** Supprime le brouillon de l'appelant ; 404 s'il n'en a pas. */
    @Transactional
    public void delete(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireEditableDocument(user.getId(), documentId);
        int deleted = jdbc.update(
                "DELETE FROM document_drafts WHERE document_id = ? AND user_id = ?",
                documentId, user.getId());
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillon introuvable");
        }
    }

    /**
     * Effacement de tous les brouillons d'un utilisateur (anonymisation / purge RGPD).
     * Les brouillons sont des données personnelles non versionnées : ils ne survivent pas
     * à l'anonymisation du compte.
     */
    @Transactional
    public int deleteAllForUser(UUID userId) {
        return jdbc.update("DELETE FROM document_drafts WHERE user_id = ?", userId);
    }

    /** Export RGPD : brouillons en cours de l'utilisateur (métadonnées + contenu). */
    @Transactional(readOnly = true)
    public List<PersonalDraftExport> exportFor(UUID userId) {
        return jdbc.query("""
                SELECT document_id, title, body, base_version_no, updated_at
                  FROM document_drafts
                 WHERE user_id = ?
                 ORDER BY updated_at DESC, document_id
                """,
                (rs, i) -> new PersonalDraftExport(
                        (UUID) rs.getObject("document_id"),
                        rs.getString("title"),
                        parseBody(rs.getString("body")),
                        rs.getInt("base_version_no"),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
    }

    public record PersonalDraftExport(
            UUID documentId, String title, Map<String, Object> body, int baseVersionNo, Instant updatedAt) {}

    private java.util.Optional<DraftView> find(UUID documentId, UUID userId) {
        return jdbc.query("""
                        SELECT title, body, base_version_no, updated_at
                          FROM document_drafts
                         WHERE document_id = ? AND user_id = ?
                        """,
                (rs, i) -> new DraftView(
                        rs.getString("title"),
                        parseBody(rs.getString("body")),
                        rs.getInt("base_version_no"),
                        rs.getTimestamp("updated_at").toInstant()),
                documentId, userId).stream().findFirst();
    }

    private Map<String, Object> parseBody(String json) {
        try {
            return objectMapper.readValue(json, BODY_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Brouillon corrompu (JSON invalide)", e);
        }
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
}
