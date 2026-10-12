// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvitationRepository {

    private final JdbcTemplate jdbc;

    public InvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<InvitationRow> MAPPER = (rs, i) -> new InvitationRow(
            (UUID) rs.getObject("id"),
            rs.getString("email"),
            (UUID) rs.getObject("team_id"),
            rs.getString("team_role"),
            rs.getString("global_role"),
            rs.getBytes("token_hash"),
            rs.getString("status"),
            ts(rs.getTimestamp("expires_at")),
            (UUID) rs.getObject("invited_by"),
            (UUID) rs.getObject("accepted_user_id"),
            ts(rs.getTimestamp("accepted_at")),
            ts(rs.getTimestamp("created_at")),
            ts(rs.getTimestamp("last_sent_at")),
            rs.getInt("send_count"));

    public void insert(InvitationRow row) {
        jdbc.update("""
                INSERT INTO invitations (
                    id, email, team_id, team_role, global_role, token_hash, status,
                    expires_at, invited_by, created_at, last_sent_at, send_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), row.email(), row.teamId(), row.teamRole(), row.globalRole(),
                row.tokenHash(), row.status(), Timestamp.from(row.expiresAt()),
                row.invitedBy(), Timestamp.from(row.createdAt()),
                Timestamp.from(row.lastSentAt()), row.sendCount());
    }

    public Optional<InvitationRow> findById(UUID id) {
        List<InvitationRow> rows = jdbc.query("SELECT * FROM invitations WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<InvitationRow> findPendingByEmail(String email) {
        List<InvitationRow> rows = jdbc.query(
                "SELECT * FROM invitations WHERE email = ? AND status = 'pending'", MAPPER, email);
        return rows.stream().findFirst();
    }

    public Optional<InvitationRow> findByTokenHash(byte[] hash) {
        List<InvitationRow> rows = jdbc.query(
                "SELECT * FROM invitations WHERE token_hash = ? FOR UPDATE", MAPPER, hash);
        return rows.stream().findFirst();
    }

    public boolean hasPendingForEmail(String email) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM invitations WHERE email = ? AND status = 'pending'",
                Integer.class, email);
        return n != null && n > 0;
    }

    public List<InvitationRow> listPending() {
        return jdbc.query(
                "SELECT * FROM invitations WHERE status = 'pending' ORDER BY created_at DESC", MAPPER);
    }

    public int markAccepted(UUID id, UUID userId, Instant at) {
        return jdbc.update("""
                UPDATE invitations
                   SET status = 'accepted', accepted_user_id = ?, accepted_at = ?
                 WHERE id = ? AND status = 'pending' AND expires_at > ?
                """,
                userId, Timestamp.from(at), id, Timestamp.from(at));
    }

    public int replaceToken(UUID id, byte[] newHash, Instant sentAt) {
        return jdbc.update("""
                UPDATE invitations
                   SET token_hash = ?, last_sent_at = ?, send_count = send_count + 1
                 WHERE id = ? AND status = 'pending'
                """,
                newHash, Timestamp.from(sentAt), id);
    }

    public int revoke(UUID id) {
        return jdbc.update(
                "UPDATE invitations SET status = 'revoked' WHERE id = ? AND status = 'pending'", id);
    }

    public List<UUID> expireDue(Instant now) {
        return jdbc.query(
                "UPDATE invitations SET status = 'expired' WHERE status = 'pending' AND expires_at <= ? RETURNING id",
                (rs, i) -> (UUID) rs.getObject("id"),
                Timestamp.from(now));
    }

    public String teamName(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        List<String> names = jdbc.query(
                "SELECT name FROM groups WHERE id = ?", (rs, i) -> rs.getString(1), teamId);
        return names.isEmpty() ? null : names.getFirst();
    }

    public String userDisplayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        List<String> names = jdbc.query(
                "SELECT display_name FROM users WHERE id = ?", (rs, i) -> rs.getString(1), userId);
        return names.isEmpty() ? null : names.getFirst();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
