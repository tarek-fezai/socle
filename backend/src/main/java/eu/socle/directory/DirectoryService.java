// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import eu.socle.directory.DirectoryDtos.AdminTeamRef;
import eu.socle.directory.DirectoryDtos.AdminUserRow;
import eu.socle.directory.DirectoryDtos.AdminUsersResponse;
import eu.socle.directory.DirectoryDtos.DirectoryUser;
import eu.socle.directory.DirectoryDtos.DirectoryUsersResponse;
import eu.socle.user.AccentFold;
import eu.socle.user.Initials;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Annuaire nominatif + liste admin. Voir {@code docs/teams-and-access.md} § 2.
 */
@Service
public class DirectoryService {

    static final int MIN_Q = 2;
    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 20;
    static final int ADMIN_DEFAULT_LIMIT = 50;
    static final int ADMIN_MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final DirectoryProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, RateWindow> rate = new ConcurrentHashMap<>();

    public DirectoryService(JdbcTemplate jdbc, DirectoryProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public DirectoryUsersResponse searchUsers(UUID callerId, String q, Integer limit) {
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < MIN_Q) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "q obligatoire (≥ 2 caractères)");
        }
        enforceRateLimit(callerId);
        int cap = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        String like = AccentFold.likeContains(trimmed);
        List<DirectoryUser> users = jdbc.query(
                """
                SELECT id, display_name, email, avatar_initials
                  FROM users
                 WHERE status = 'active'
                   AND (
                     unaccent(lower(display_name)) LIKE unaccent(lower(?)) ESCAPE '\\'
                     OR unaccent(lower(email)) LIKE unaccent(lower(?)) ESCAPE '\\'
                   )
                 ORDER BY display_name ASC
                 LIMIT ?
                """,
                (rs, i) -> new DirectoryUser(
                        (UUID) rs.getObject("id"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        Initials.of(rs.getString("display_name"), rs.getString("avatar_initials"))),
                like, like, cap);
        return new DirectoryUsersResponse(users);
    }

    public AdminUsersResponse listAdmin(
            String q,
            String status,
            String platformRole,
            UUID groupId,
            Integer offset,
            Integer limit
    ) {
        int off = offset == null || offset < 0 ? 0 : offset;
        int cap = limit == null ? ADMIN_DEFAULT_LIMIT : Math.min(Math.max(1, limit), ADMIN_MAX_LIMIT);
        String statusFilter = (status == null || status.isBlank()) ? null : status.trim();
        if (statusFilter != null && !"active".equals(statusFilter) && !"disabled".equals(statusFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status invalide (active|disabled)");
        }
        String roleFilter = (platformRole == null || platformRole.isBlank()) ? null : platformRole.trim();
        String like = (q != null && q.trim().length() >= MIN_Q) ? AccentFold.likeContains(q) : null;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (statusFilter != null) {
            where.append(" AND u.status = ? ");
            args.add(statusFilter);
        }
        if (like != null) {
            where.append("""
                     AND (
                       unaccent(lower(u.display_name)) LIKE unaccent(lower(?)) ESCAPE '\\'
                       OR unaccent(lower(u.email)) LIKE unaccent(lower(?)) ESCAPE '\\'
                     )
                    """);
            args.add(like);
            args.add(like);
        }
        if (roleFilter != null) {
            where.append("""
                     AND EXISTS (
                       SELECT 1 FROM user_platform_roles pr
                        WHERE pr.user_id = u.id AND pr.role = ?
                     )
                    """);
            args.add(roleFilter);
        }
        if (groupId != null) {
            where.append("""
                     AND EXISTS (
                       SELECT 1 FROM group_members gm
                        WHERE gm.user_id = u.id AND gm.group_id = ?
                     )
                    """);
            args.add(groupId);
        }

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM users u" + where, Integer.class, args.toArray());
        Integer activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status = 'active'", Integer.class);
        Integer teamCount = jdbc.queryForObject("SELECT count(*) FROM groups", Integer.class);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(cap);
        pageArgs.add(off);
        List<AdminUserRow> slim = jdbc.query(
                """
                SELECT u.id, u.display_name, u.email, u.avatar_initials, u.status, u.last_login_at
                  FROM users u
                """ + where + """
                 ORDER BY u.display_name ASC
                 LIMIT ? OFFSET ?
                """,
                (rs, i) -> new AdminUserRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        Initials.of(rs.getString("display_name"), rs.getString("avatar_initials")),
                        List.of(),
                        List.of(),
                        rs.getString("status"),
                        ts(rs.getTimestamp("last_login_at"))),
                pageArgs.toArray());

        if (slim.isEmpty()) {
            return new AdminUsersResponse(
                    List.of(),
                    total == null ? 0 : total,
                    activeCount == null ? 0 : activeCount,
                    teamCount == null ? 0 : teamCount,
                    off,
                    cap);
        }

        UUID[] ids = slim.stream().map(AdminUserRow::id).toArray(UUID[]::new);
        Map<UUID, List<String>> roles = new LinkedHashMap<>();
        jdbc.query(
                con -> {
                    var ps = con.prepareStatement(
                            "SELECT user_id, role FROM user_platform_roles WHERE user_id = ANY (?) ORDER BY role");
                    ps.setArray(1, con.createArrayOf("uuid", ids));
                    return ps;
                },
                rs -> {
                    UUID uid = (UUID) rs.getObject("user_id");
                    roles.computeIfAbsent(uid, k -> new ArrayList<>()).add(rs.getString("role"));
                });
        Map<UUID, List<AdminTeamRef>> teams = new LinkedHashMap<>();
        jdbc.query(
                con -> {
                    var ps = con.prepareStatement(
                            """
                            SELECT gm.user_id, g.id, g.name, gm.role
                              FROM group_members gm
                              JOIN groups g ON g.id = gm.group_id
                             WHERE gm.user_id = ANY (?)
                             ORDER BY g.name
                            """);
                    ps.setArray(1, con.createArrayOf("uuid", ids));
                    return ps;
                },
                rs -> {
                    UUID uid = (UUID) rs.getObject("user_id");
                    teams.computeIfAbsent(uid, k -> new ArrayList<>()).add(new AdminTeamRef(
                            (UUID) rs.getObject("id"),
                            rs.getString("name"),
                            rs.getString("role")));
                });

        List<AdminUserRow> rows = slim.stream()
                .map(r -> new AdminUserRow(
                        r.id(),
                        r.displayName(),
                        r.email(),
                        r.initials(),
                        List.copyOf(roles.getOrDefault(r.id(), List.of())),
                        List.copyOf(teams.getOrDefault(r.id(), List.of())),
                        r.status(),
                        r.lastActivityAt()))
                .toList();
        return new AdminUsersResponse(
                rows,
                total == null ? 0 : total,
                activeCount == null ? 0 : activeCount,
                teamCount == null ? 0 : teamCount,
                off,
                cap);
    }

    private void enforceRateLimit(UUID userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        RateWindow w = rate.compute(userId, (id, prev) -> {
            if (prev == null || prev.minute != minute) {
                return new RateWindow(minute, new AtomicInteger(1));
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (w.count.get() > Math.max(1, properties.getRateLimitPerMinute())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite d'annuaire atteinte");
        }
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private record RateWindow(long minute, AtomicInteger count) {}
}
