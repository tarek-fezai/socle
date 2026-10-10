// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès {@code personal_access_tokens} — le hash ne sort jamais de ce paquet. */
@Repository
public class PatRepository {

    /** Ligne publique (sans hash). */
    public record PatRow(
            UUID id,
            UUID userId,
            String name,
            String last4,
            PatScope scope,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            String revokedReason
    ) {}

    /** Résolution d'un jeton présenté : ligne + compte + première identité liée. */
    record AuthRow(
            UUID id,
            UUID userId,
            byte[] tokenHash,
            PatScope scope,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            String userStatus,
            String email,
            String displayName
    ) {
        @Override
        public String toString() {
            return "PatRepository.AuthRow[id=" + id + ", userId=" + userId + "]";
        }
    }

    record Identity(String issuer, String subject) {}

    private static final String COLUMNS = """
            id, user_id, name, last4, scope, created_at, expires_at, last_used_at, revoked_at, revoked_reason
            """;

    private static final RowMapper<PatRow> ROW = (rs, n) -> new PatRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("name"),
            rs.getString("last4"),
            PatScope.fromValue(rs.getString("scope")),
            instant(rs, "created_at"),
            instant(rs, "expires_at"),
            instant(rs, "last_used_at"),
            instant(rs, "revoked_at"),
            rs.getString("revoked_reason"));

    private final JdbcTemplate jdbc;

    public PatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID userId, String name, String lookup, byte[] hash, String last4,
                PatScope scope, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO personal_access_tokens
                  (id, user_id, name, token_lookup, token_hash, last4, scope, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, userId, name, lookup, hash, last4, scope.value(),
                Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public List<PatRow> findByUser(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM personal_access_tokens WHERE user_id = ? "
                + "ORDER BY created_at DESC, id", ROW, userId);
    }

    Optional<PatRow> findByIdAndUser(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM personal_access_tokens WHERE id = ? AND user_id = ?",
                ROW, id, userId).stream().findFirst();
    }

    /** Verrou sur le compte : sérialise les créations concurrentes (limite de jetons actifs). */
    void lockUser(UUID userId) {
        jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", rs -> null, userId);
    }

    int countActive(UUID userId, Instant now) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM personal_access_tokens
                 WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
                """, Integer.class, userId, Timestamp.from(now));
        return n == null ? 0 : n;
    }

    boolean revoke(UUID id, Instant now, String reason) {
        return jdbc.update("""
                UPDATE personal_access_tokens SET revoked_at = ?, revoked_reason = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, Timestamp.from(now), reason, id) == 1;
    }

    List<PatRow> revokeAllForUser(UUID userId, Instant now, String reason) {
        return jdbc.query("""
                UPDATE personal_access_tokens SET revoked_at = ?, revoked_reason = ?
                 WHERE user_id = ? AND revoked_at IS NULL
                RETURNING
                """ + COLUMNS, ROW, Timestamp.from(now), reason, userId);
    }

    Optional<AuthRow> findForAuthentication(String lookup) {
        return jdbc.query("""
                SELECT t.id, t.user_id, t.token_hash, t.scope, t.expires_at, t.last_used_at, t.revoked_at,
                       u.status, u.email, u.display_name
                  FROM personal_access_tokens t
                  JOIN users u ON u.id = t.user_id
                 WHERE t.token_lookup = ?
                """, (rs, n) -> new AuthRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getBytes("token_hash"),
                        PatScope.fromValue(rs.getString("scope")),
                        instant(rs, "expires_at"),
                        instant(rs, "last_used_at"),
                        instant(rs, "revoked_at"),
                        rs.getString("status"),
                        rs.getString("email"),
                        rs.getString("display_name")),
                lookup).stream().findFirst();
    }

    Optional<Identity> firstIdentity(UUID userId) {
        return jdbc.query("""
                SELECT issuer, subject FROM user_identities
                 WHERE user_id = ?
                 ORDER BY linked_at, issuer, subject
                 LIMIT 1
                """, (rs, n) -> new Identity(rs.getString("issuer"), rs.getString("subject")), userId)
                .stream().findFirst();
    }

    /** Au plus une écriture par minute et par jeton (garde aussi en SQL). */
    void touchLastUsed(UUID id, Instant now) {
        jdbc.update("""
                UPDATE personal_access_tokens SET last_used_at = ?
                 WHERE id = ? AND (last_used_at IS NULL OR last_used_at <= ?)
                """, Timestamp.from(now), id, Timestamp.from(now.minusSeconds(60)));
    }

    /** Notification J-7, une seule par jeton (index unique partiel V46). */
    int insertExpiryNotifications(Instant now, Instant horizon) {
        return jdbc.update("""
                INSERT INTO notifications (user_id, type, payload, created_at)
                SELECT t.user_id, 'pat_expiring',
                       jsonb_build_object(
                         'pat_id', t.id::text,
                         'name', t.name,
                         'last4', t.last4,
                         'expires_at', to_char(t.expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
                       ?
                  FROM personal_access_tokens t
                  JOIN users u ON u.id = t.user_id
                 WHERE t.revoked_at IS NULL
                   AND t.expires_at > ?
                   AND t.expires_at <= ?
                   AND u.status = 'active'
                ON CONFLICT ((payload ->> 'pat_id')) WHERE type = 'pat_expiring' DO NOTHING
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(horizon));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
