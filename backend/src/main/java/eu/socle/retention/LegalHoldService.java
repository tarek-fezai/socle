// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.retention.LegalHoldDtos.LegalHoldListResponse;
import eu.socle.retention.LegalHoldDtos.LegalHoldView;
import eu.socle.retention.LegalHoldDtos.PlaceLegalHoldRequest;
import eu.socle.retention.LegalHoldDtos.ReleaseLegalHoldRequest;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gel légal (« legal hold ») sur un document ou un espace — réservé {@code ADMINISTRATEUR_SYSTEME}
 * pour poser / lever (motif obligatoire, audité).
 *
 * <p>Tant qu'un gel est actif (document, ou espace contenant le document), la suppression
 * (corbeille), la purge (corbeille, rétention, pièces jointes) et l'effacement RGPD des données
 * rattachées renvoient {@code 409 legal_hold_active}.
 */
@Service
public class LegalHoldService {

    public static final String SCOPE_DOCUMENT = "document";
    public static final String SCOPE_SPACE = "space";
    public static final int MAX_REASON_LENGTH = 2000;

    private static final int IN_CHUNK = 500;

    private static final RowMapper<LegalHoldView> MAPPER = (rs, i) -> {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp released = rs.getTimestamp("released_at");
        return new LegalHoldView(
                (UUID) rs.getObject("id"),
                rs.getString("scope_type"),
                (UUID) rs.getObject("scope_id"),
                rs.getString("scope_label"),
                rs.getString("reason"),
                (UUID) rs.getObject("created_by"),
                rs.getString("created_by_display"),
                created == null ? null : created.toInstant(),
                (UUID) rs.getObject("released_by"),
                rs.getString("released_by_display"),
                released == null ? null : released.toInstant(),
                rs.getString("release_reason"),
                released == null);
    };

    private static final String SELECT_VIEW = """
            SELECT h.id, h.scope_type, h.scope_id, h.reason, h.created_by, h.created_at,
                   h.released_by, h.released_at, h.release_reason,
                   CASE h.scope_type
                        WHEN 'document' THEN (SELECT d.title FROM documents d WHERE d.id = h.scope_id)
                        ELSE (SELECT s.name FROM spaces s WHERE s.id = h.scope_id)
                   END AS scope_label,
                   cu.display_name AS created_by_display,
                   ru.display_name AS released_by_display
              FROM legal_holds h
              LEFT JOIN users cu ON cu.id = h.created_by
              LEFT JOIN users ru ON ru.id = h.released_by
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;

    public LegalHoldService(JdbcTemplate jdbc, IdentityFacade identityFacade, AuditService auditService) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.identityFacade = identityFacade;
        this.auditService = auditService;
    }

    // ── Administration ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LegalHoldListResponse list(Jwt jwt, boolean activeOnly) {
        requireAdmin(jwt);
        List<LegalHoldView> holds = jdbc.query(
                SELECT_VIEW
                        + (activeOnly ? " WHERE h.released_at IS NULL" : "")
                        + " ORDER BY h.created_at DESC, h.id",
                MAPPER);
        long active = holds.stream().filter(LegalHoldView::active).count();
        if (!activeOnly) {
            return new LegalHoldListResponse(holds, active);
        }
        return new LegalHoldListResponse(holds, holds.size());
    }

    @Transactional
    public LegalHoldView place(Jwt jwt, PlaceLegalHoldRequest request) {
        UserEntity admin = requireAdmin(jwt);
        String scopeType = normalizeScopeType(request == null ? null : request.scopeType());
        UUID scopeId = request == null ? null : request.scopeId();
        if (scopeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeId requis");
        }
        String reason = requireReason(request.reason());
        requireScopeExists(scopeType, scopeId);
        if (activeHoldExists(scopeType, scopeId)) {
            throw ApiErrors.legalHoldAlreadyActive(scopeType, scopeId);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO legal_holds (id, scope_type, scope_id, reason, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, scopeType, scopeId, reason, admin.getId());
        } catch (DuplicateKeyException e) {
            throw ApiErrors.legalHoldAlreadyActive(scopeType, scopeId);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scopeType", scopeType);
        meta.put("scopeId", scopeId.toString());
        meta.put("reason", reason);
        auditService.record(admin.getId(), false, AuditActions.LEGAL_HOLD_PLACED, "legal_hold", id, meta, null);
        return requireView(id);
    }

    @Transactional
    public LegalHoldView release(Jwt jwt, UUID holdId, ReleaseLegalHoldRequest request) {
        UserEntity admin = requireAdmin(jwt);
        String reason = requireReason(request == null ? null : request.reason());
        LegalHoldView current = requireView(holdId);
        if (!current.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal hold déjà levé");
        }
        int updated = jdbc.update("""
                UPDATE legal_holds
                   SET released_at = now(), released_by = ?, release_reason = ?
                 WHERE id = ? AND released_at IS NULL
                """, admin.getId(), reason, holdId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal hold déjà levé");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scopeType", current.scopeType());
        meta.put("scopeId", current.scopeId().toString());
        meta.put("placedReason", current.reason());
        meta.put("reason", reason);
        auditService.record(admin.getId(), false, AuditActions.LEGAL_HOLD_RELEASED, "legal_hold", holdId, meta, null);
        return requireView(holdId);
    }

    // ── Garde-fous (appelés par Trash / Attachment / RGPD / rétention) ──────

    /** Lève {@code 409 legal_hold_active} si un gel couvre le document (document OU espace). */
    @Transactional(readOnly = true)
    public void assertDocumentNotHeld(UUID documentId) {
        if (documentId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT h.scope_type, h.scope_id
                  FROM legal_holds h
                 WHERE h.released_at IS NULL
                   AND ((h.scope_type = 'document' AND h.scope_id = ?)
                     OR (h.scope_type = 'space'
                         AND h.scope_id = (SELECT d.space_id FROM documents d WHERE d.id = ?)))
                 ORDER BY CASE h.scope_type WHEN 'document' THEN 0 ELSE 1 END
                 LIMIT 1
                """,
                (rs, i) -> Map.<String, Object>of(
                        "type", rs.getString("scope_type"),
                        "id", rs.getObject("scope_id")),
                documentId, documentId);
        if (!rows.isEmpty()) {
            throw ApiErrors.legalHoldActive((String) rows.getFirst().get("type"), (UUID) rows.getFirst().get("id"));
        }
    }

    public boolean isDocumentHeld(UUID documentId) {
        try {
            assertDocumentNotHeld(documentId);
            return false;
        } catch (eu.socle.web.CodedStatusException e) {
            if (ApiErrors.LEGAL_HOLD_ACTIVE.equals(e.getCode())) {
                return true;
            }
            throw e;
        }
    }

    /** Premier document gelé de la liste → 409 ; sinon no-op. */
    @Transactional(readOnly = true)
    public void assertDocumentsNotHeld(Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(documentIds));
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
            List<Map<String, Object>> rows = namedJdbc.query("""
                    SELECT h.scope_type, h.scope_id
                      FROM legal_holds h
                     WHERE h.released_at IS NULL
                       AND ((h.scope_type = 'document' AND h.scope_id IN (:ids))
                         OR (h.scope_type = 'space'
                             AND h.scope_id IN (SELECT d.space_id FROM documents d WHERE d.id IN (:ids))))
                     LIMIT 1
                    """,
                    new MapSqlParameterSource("ids", chunk),
                    (rs, i) -> Map.<String, Object>of(
                            "type", rs.getString("scope_type"),
                            "id", rs.getObject("scope_id")));
            if (!rows.isEmpty()) {
                throw ApiErrors.legalHoldActive(
                        (String) rows.getFirst().get("type"), (UUID) rows.getFirst().get("id"));
            }
        }
    }

    /** Sous-ensemble des documents couverts par un gel (pour les purges planifiées qui « sautent »). */
    @Transactional(readOnly = true)
    public Set<UUID> heldDocumentIds(Collection<UUID> documentIds) {
        Set<UUID> held = new LinkedHashSet<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return held;
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(documentIds));
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
            held.addAll(namedJdbc.query("""
                    SELECT d.id
                      FROM documents d
                     WHERE d.id IN (:ids)
                       AND EXISTS (
                         SELECT 1 FROM legal_holds h
                          WHERE h.released_at IS NULL
                            AND ((h.scope_type = 'document' AND h.scope_id = d.id)
                              OR (h.scope_type = 'space' AND h.scope_id = d.space_id)))
                    """,
                    new MapSqlParameterSource("ids", chunk),
                    (rs, i) -> (UUID) rs.getObject("id")));
        }
        return held;
    }

    /**
     * Suppression d'un espace : gel sur l'espace lui-même OU sur l'un de ses documents → 409.
     */
    @Transactional(readOnly = true)
    public void assertSpaceNotHeld(UUID spaceId) {
        if (spaceId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT h.scope_type, h.scope_id
                  FROM legal_holds h
                 WHERE h.released_at IS NULL
                   AND ((h.scope_type = 'space' AND h.scope_id = ?)
                     OR (h.scope_type = 'document'
                         AND h.scope_id IN (SELECT d.id FROM documents d WHERE d.space_id = ?)))
                 ORDER BY CASE h.scope_type WHEN 'space' THEN 0 ELSE 1 END
                 LIMIT 1
                """,
                (rs, i) -> Map.<String, Object>of(
                        "type", rs.getString("scope_type"),
                        "id", rs.getObject("scope_id")),
                spaceId, spaceId);
        if (!rows.isEmpty()) {
            throw ApiErrors.legalHoldActive((String) rows.getFirst().get("type"), (UUID) rows.getFirst().get("id"));
        }
    }

    public boolean isSpaceHeld(UUID spaceId) {
        try {
            assertSpaceNotHeld(spaceId);
            return false;
        } catch (eu.socle.web.CodedStatusException e) {
            if (ApiErrors.LEGAL_HOLD_ACTIVE.equals(e.getCode())) {
                return true;
            }
            throw e;
        }
    }

    /**
     * Effacement / anonymisation RGPD d'un utilisateur : refusé si ses commentaires ou brouillons
     * portent sur un document couvert par un gel.
     */
    @Transactional(readOnly = true)
    public void assertUserDataNotHeld(UUID userId) {
        if (userId == null) {
            return;
        }
        List<UUID> docIds = jdbc.query("""
                SELECT DISTINCT document_id FROM (
                    SELECT document_id FROM document_comments WHERE author_id = ?
                    UNION ALL
                    SELECT document_id FROM document_drafts WHERE user_id = ?
                ) x
                """,
                (rs, i) -> (UUID) rs.getObject(1),
                userId, userId);
        assertDocumentsNotHeld(docIds);
    }

    // ── Interne ─────────────────────────────────────────────────────────────

    private boolean activeHoldExists(String scopeType, UUID scopeId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM legal_holds
                 WHERE scope_type = ? AND scope_id = ? AND released_at IS NULL
                """, Integer.class, scopeType, scopeId);
        return n != null && n > 0;
    }

    private void requireScopeExists(String scopeType, UUID scopeId) {
        String sql = SCOPE_DOCUMENT.equals(scopeType)
                ? "SELECT count(*) FROM documents WHERE id = ?"
                : "SELECT count(*) FROM spaces WHERE id = ?";
        Integer n = jdbc.queryForObject(sql, Integer.class, scopeId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    SCOPE_DOCUMENT.equals(scopeType) ? "Document introuvable" : "Espace introuvable");
        }
    }

    private LegalHoldView requireView(UUID id) {
        List<LegalHoldView> rows = jdbc.query(SELECT_VIEW + " WHERE h.id = ?", MAPPER, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Legal hold introuvable");
        }
        return rows.getFirst();
    }

    private static String normalizeScopeType(String raw) {
        if (raw != null) {
            String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (SCOPE_DOCUMENT.equals(t) || SCOPE_SPACE.equals(t)) {
                return t;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeType invalide (document|space)");
    }

    private static String requireReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiErrors.legalHoldReasonRequired();
        }
        String reason = raw.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Motif trop long (maximum " + MAX_REASON_LENGTH + " caractères)");
        }
        return reason;
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }
}
