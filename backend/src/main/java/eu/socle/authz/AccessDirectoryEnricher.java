// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import eu.socle.authz.AuthorizationService.AccessEntry;
import eu.socle.user.Initials;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Enrichit la liste d'accès avec nom / initiales — aucun UUID exposé à l'UI. */
@Component
public class AccessDirectoryEnricher {

    private final JdbcTemplate jdbc;

    public AccessDirectoryEnricher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AccessController.AccessEntryView> enrich(List<AccessEntry> entries) {
        List<UUID> userIds = entries.stream()
                .filter(e -> "user".equals(e.subjectType()) && e.subjectId() != null)
                .map(AccessEntry::subjectId)
                .distinct()
                .toList();
        List<UUID> groupIds = entries.stream()
                .filter(e -> "group".equals(e.subjectType()) && e.subjectId() != null)
                .map(AccessEntry::subjectId)
                .distinct()
                .toList();
        Map<UUID, UserRow> users = loadUsers(userIds);
        Map<UUID, GroupRow> groups = loadGroups(groupIds);
        List<AccessController.AccessEntryView> out = new ArrayList<>();
        for (AccessEntry e : entries) {
            String displayName = null;
            String initials = null;
            Instant last = null;
            Integer memberCount = null;
            if ("user".equals(e.subjectType()) && e.subjectId() != null) {
                UserRow u = users.get(e.subjectId());
                if (u != null) {
                    displayName = u.displayName;
                    initials = Initials.of(u.displayName, u.initials);
                    last = u.lastLoginAt;
                }
            } else if ("group".equals(e.subjectType()) && e.subjectId() != null) {
                GroupRow g = groups.get(e.subjectId());
                if (g != null) {
                    displayName = g.name;
                    initials = "⬡";
                    memberCount = g.memberCount;
                }
            } else if ("wildcard".equals(e.subjectType())) {
                displayName = "Tous les utilisateurs";
                initials = "*";
            }
            out.add(new AccessController.AccessEntryView(
                    e.subject(),
                    e.subjectType(),
                    e.subjectId(),
                    e.relation(),
                    e.source(),
                    e.inheritedFrom(),
                    e.revocable(),
                    displayName,
                    initials,
                    last,
                    memberCount));
        }
        return out;
    }

    private Map<UUID, UserRow> loadUsers(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UserRow> out = new LinkedHashMap<>();
        jdbc.query(
                con -> {
                    var ps = con.prepareStatement(
                            """
                            SELECT id, display_name, avatar_initials, last_login_at
                              FROM users WHERE id = ANY (?)
                            """);
                    ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
                    return ps;
                },
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Timestamp ts = rs.getTimestamp("last_login_at");
                    out.put(id, new UserRow(
                            rs.getString("display_name"),
                            rs.getString("avatar_initials"),
                            ts == null ? null : ts.toInstant()));
                });
        return out;
    }

    private Map<UUID, GroupRow> loadGroups(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, GroupRow> out = new LinkedHashMap<>();
        jdbc.query(
                con -> {
                    var ps = con.prepareStatement(
                            """
                            SELECT g.id, g.name, count(gm.user_id)::int AS member_count
                              FROM groups g
                              LEFT JOIN group_members gm ON gm.group_id = g.id
                             WHERE g.id = ANY (?)
                             GROUP BY g.id, g.name
                            """);
                    ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
                    return ps;
                },
                rs -> {
                    out.put((UUID) rs.getObject("id"), new GroupRow(
                            rs.getString("name"),
                            rs.getInt("member_count")));
                });
        return out;
    }

    private record UserRow(String displayName, String initials, Instant lastLoginAt) {}

    private record GroupRow(String name, int memberCount) {}
}
