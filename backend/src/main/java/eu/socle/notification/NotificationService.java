// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture / marquage / création des notifications de l'utilisateur courant.
 *
 * <p><b>Producteurs actuels</b> : {@code approval_chain_exhausted}
 * ({@code ApprovalActivitiesImpl}), {@code external_reference_first}
 * ({@code ExternalReferenceNotifier}).
 *
 * <p><b>Isolation</b> : toute lecture/écriture est filtrée sur {@code user_id} du JWT.
 * Accès à une notif d'autrui → {@code 404} (pas 403) pour ne pas révéler l'existence.
 */
@Service
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbcTemplate;
    private final UserSyncService userSyncService;
    private final ObjectMapper objectMapper;

    public NotificationService(
            JdbcTemplate jdbcTemplate,
            UserSyncService userSyncService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userSyncService = userSyncService;
        this.objectMapper = objectMapper;
    }

    /**
     * Écrit une notification pour {@code userId}. Réutilisé par workflows et
     * {@code external_reference} — pas un second canal parallèle.
     */
    public void create(UUID userId, String type, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            jdbcTemplate.update("""
                    INSERT INTO notifications (id, user_id, type, payload, created_at)
                    VALUES (?, ?, ?, CAST(? AS jsonb), now())
                    """,
                    UUID.randomUUID(), userId, type, json);
        } catch (Exception e) {
            throw new IllegalStateException("Échec INSERT notification type=" + type, e);
        }
    }

    public NotificationPage list(Jwt jwt, boolean unreadOnly, int offset, int limit) {
        var user = userSyncService.syncFromJwt(jwt);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        String unreadClause = unreadOnly ? " AND n.read_at IS NULL" : "";
        List<NotificationView> items = jdbcTemplate.query("""
                SELECT n.id, n.type, n.payload::text AS payload_json, n.read_at, n.created_at,
                       d.title AS document_title
                  FROM notifications n
                  LEFT JOIN documents d
                    ON d.id = NULLIF(n.payload->>'document_id', '')::uuid
                 WHERE n.user_id = ?
                """ + unreadClause + """
                 ORDER BY n.created_at DESC
                 LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapRow(rs),
                user.getId(), safeLimit, safeOffset);

        Long total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notifications n
                 WHERE n.user_id = ?
                """ + unreadClause,
                Long.class,
                user.getId());

        Long unreadCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notifications
                 WHERE user_id = ? AND read_at IS NULL
                """,
                Long.class,
                user.getId());

        return new NotificationPage(
                items,
                safeOffset,
                safeLimit,
                total != null ? total : 0,
                unreadCount != null ? unreadCount : 0
        );
    }

    /**
     * Marque comme lue. Idempotent si déjà lue. {@code 404} si absente ou hors périmètre.
     */
    public NotificationView markRead(Jwt jwt, UUID notificationId) {
        var user = userSyncService.syncFromJwt(jwt);

        int updated = jdbcTemplate.update("""
                UPDATE notifications
                   SET read_at = now()
                 WHERE id = ? AND user_id = ? AND read_at IS NULL
                """,
                notificationId, user.getId());

        List<NotificationView> rows = jdbcTemplate.query("""
                SELECT n.id, n.type, n.payload::text AS payload_json, n.read_at, n.created_at,
                       d.title AS document_title
                  FROM notifications n
                  LEFT JOIN documents d
                    ON d.id = NULLIF(n.payload->>'document_id', '')::uuid
                 WHERE n.id = ? AND n.user_id = ?
                """,
                (rs, i) -> mapRow(rs),
                notificationId, user.getId());

        if (rows.isEmpty()) {
            // Absent pour cet utilisateur (y compris notif d'un autre) → 404, pas 403
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable");
        }
        // updated == 0 si déjà lue : OK idempotent
        return rows.getFirst();
    }

    private NotificationView mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> payload = parsePayload(rs.getString("payload_json"));
        Timestamp readAt = rs.getTimestamp("read_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new NotificationView(
                (UUID) rs.getObject("id"),
                rs.getString("type"),
                payload,
                rs.getString("document_title"),
                readAt != null ? readAt.toInstant().toString() : null,
                createdAt != null ? createdAt.toInstant().toString() : null
        );
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    public record NotificationView(
            UUID id,
            String type,
            Map<String, Object> payload,
            String documentTitle,
            String readAt,
            String createdAt
    ) {}

    public record NotificationPage(
            List<NotificationView> items,
            int offset,
            int limit,
            long total,
            long unreadCount
    ) {}
}
