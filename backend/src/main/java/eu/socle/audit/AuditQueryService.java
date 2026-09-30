package eu.socle.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lecture du journal d'audit avec filtres serveur (pas de filtre mémoire côté UI).
 *
 * <p><b>Transversalité :</b> aucune jointure ni filtre sur appartenance d'espace,
 * {@code space_owners}, {@code external_reference} ou OpenFGA. Un auditeur realm
 * voit toute l'activité de gouvernance de l'instance — la gouvernance locale ne
 * peut pas restreindre cette lecture. Voir {@code docs/audit-logging.md}.
 *
 * <p>Paramètre {@code action} : exact ({@code access.granted}), préfixe ({@code access.*}
 * ou {@code access.}), ou liste séparée par virgules.
 */
@Service
public class AuditQueryService {

    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;

    public AuditQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AuditPage list(
            String resourceType,
            UUID resourceId,
            UUID actorId,
            String action,
            Instant since,
            Instant until,
            int offset,
            int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, resourceType, resourceId, actorId, action, since, until);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log_events e" + where,
                Long.class,
                args.toArray());

        String sql = """
                SELECT e.id, e.actor_id, e.actor_is_system, e.action, e.resource_type, e.resource_id,
                       e.metadata::text AS metadata, host(e.ip_address)::text AS ip_address, e.created_at,
                       u.display_name AS actor_display_name, u.email AS actor_email
                  FROM audit_log_events e
                  LEFT JOIN users u ON u.id = e.actor_id
                """ + where + """
                 ORDER BY e.created_at DESC
                 LIMIT ? OFFSET ?
                """;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeLimit);
        pageArgs.add(safeOffset);

        List<AuditEventView> items = jdbcTemplate.query(sql, ROW_MAPPER, pageArgs.toArray());
        return new AuditPage(items, safeOffset, safeLimit, total != null ? total : 0);
    }

    static void appendFilters(
            StringBuilder where,
            List<Object> args,
            String resourceType,
            UUID resourceId,
            UUID actorId,
            String action,
            Instant since,
            Instant until
    ) {
        if (resourceType != null && !resourceType.isBlank()) {
            where.append(" AND e.resource_type = ?");
            args.add(resourceType.trim());
        }
        if (resourceId != null) {
            where.append(" AND e.resource_id = ?");
            args.add(resourceId);
        }
        if (actorId != null) {
            where.append(" AND e.actor_id = ?");
            args.add(actorId);
        }
        if (since != null) {
            where.append(" AND e.created_at >= ?");
            args.add(Timestamp.from(since));
        }
        if (until != null) {
            where.append(" AND e.created_at <= ?");
            args.add(Timestamp.from(until));
        }
        appendActionFilter(where, args, action);
    }

    static void appendActionFilter(StringBuilder where, List<Object> args, String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        String[] parts = action.split(",");
        List<String> clauses = new ArrayList<>();
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.endsWith(".*")) {
                String prefix = part.substring(0, part.length() - 2);
                clauses.add("e.action LIKE ?");
                args.add(prefix + ".%");
            } else if (part.endsWith(".") && part.indexOf('*') < 0) {
                clauses.add("e.action LIKE ?");
                args.add(part + "%");
            } else {
                clauses.add("e.action = ?");
                args.add(part);
            }
        }
        if (!clauses.isEmpty()) {
            where.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }
    }

    private static final RowMapper<AuditEventView> ROW_MAPPER = (rs, i) -> new AuditEventView(
            rs.getLong("id"),
            (UUID) rs.getObject("actor_id"),
            rs.getBoolean("actor_is_system"),
            rs.getString("actor_display_name"),
            rs.getString("actor_email"),
            rs.getString("action"),
            rs.getString("resource_type"),
            (UUID) rs.getObject("resource_id"),
            rs.getString("metadata"),
            rs.getString("ip_address"),
            rs.getTimestamp("created_at").toInstant().toString()
    );

    public record AuditEventView(
            long id,
            UUID actorId,
            boolean actorIsSystem,
            String actorDisplayName,
            String actorEmail,
            String action,
            String resourceType,
            UUID resourceId,
            String metadata,
            String ipAddress,
            String createdAt
    ) {}

    public record AuditPage(List<AuditEventView> items, int offset, int limit, long total) {}
}
