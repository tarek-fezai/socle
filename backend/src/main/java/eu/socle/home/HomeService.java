// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.home;

import eu.socle.activity.ActivityEventService;
import eu.socle.activity.ActivityEventTypes;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentApprovalService;
import eu.socle.document.DocumentApprovalService.ApprovalView;
import eu.socle.home.HomeDtos.HomeResponse;
import eu.socle.home.HomeDtos.Kpis;
import eu.socle.home.HomeDtos.PendingApprovalItem;
import eu.socle.home.HomeDtos.RecentlyPublishedItem;
import eu.socle.home.HomeDtos.ResumeItem;
import eu.socle.home.HomeDtos.TeamActivityItem;
import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agrégat home — SQL préselect → un batch OpenFGA ({@link AuthorizationService#listViewableDocumentIds})
 * → calculs en mémoire / SQL borné à l'ensemble viewable.
 */
@Service
public class HomeService {

    private static final int RESUME_LIMIT = 5;
    private static final int RECENT_PUBLISHED_LIMIT = 8;
    private static final int TEAM_ACTIVITY_LIMIT = 10;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final IdentityClaimsMapper claimsMapper;
    private final ObjectProvider<DocumentApprovalService> approvalService;
    private final ActivityEventService activityEventService;
    private final Clock clock;

    public HomeService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            IdentityClaimsMapper claimsMapper,
            ObjectProvider<DocumentApprovalService> approvalService,
            ActivityEventService activityEventService,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.claimsMapper = claimsMapper;
        this.approvalService = approvalService;
        this.activityEventService = activityEventService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public HomeResponse getHome(Jwt jwt) {
        // Purge opportuniste (léger) en plus du scheduler.
        activityEventService.purgeOlderThanRetention();

        UserEntity user = userSyncService.syncFromJwt(jwt);
        List<UUID> viewable = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.global());
        Set<UUID> viewableSet = new HashSet<>(viewable);

        String greeting = resolveGreeting(jwt, user);
        Kpis kpis = computeKpis(jwt, viewable);
        List<ResumeItem> resume = loadResume(user.getId(), viewableSet);
        List<RecentlyPublishedItem> recentlyPublished = loadRecentlyPublished(viewable);
        List<PendingApprovalItem> pending = loadPendingApprovals(jwt, user.getId());
        List<TeamActivityItem> activity = loadTeamActivity(user.getId(), viewableSet);

        return new HomeResponse(greeting, kpis, resume, recentlyPublished, pending, activity);
    }

    private String resolveGreeting(Jwt jwt, UserEntity user) {
        String given = claimsMapper.givenName(jwt);
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        String display = user.getDisplayName();
        if (display == null || display.isBlank()) {
            return "";
        }
        String first = display.trim().split("\\s+")[0];
        return first;
    }

    private Kpis computeKpis(Jwt jwt, List<UUID> viewable) {
        long published = 0;
        Integer avgReliability = null;
        if (!viewable.isEmpty()) {
            String in = placeholders(viewable.size());
            Object[] args = viewable.toArray();
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE status = 'valide') AS published,
                           AVG(reliability_score) FILTER (
                             WHERE status = 'valide' AND reliability_score IS NOT NULL
                           ) AS avg_rel
                      FROM documents
                     WHERE deleted_at IS NULL
                       AND id IN (%s)
                    """.formatted(in),
                    args);
            published = ((Number) row.get("published")).longValue();
            Object avg = row.get("avg_rel");
            if (avg instanceof BigDecimal bd) {
                avgReliability = bd.setScale(0, RoundingMode.HALF_UP).intValue();
            } else if (avg instanceof Number n) {
                avgReliability = (int) Math.round(n.doubleValue());
            }
        }

        long pendingCount = 0;
        DocumentApprovalService approvals = approvalService.getIfAvailable();
        if (approvals != null) {
            pendingCount = approvals.listMine(jwt).size();
        }

        long viewsThisMonth = 0;
        if (!viewable.isEmpty()) {
            YearMonth ym = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            String in = placeholders(viewable.size());
            List<Object> args = new ArrayList<>();
            args.add(java.sql.Date.valueOf(start));
            args.add(java.sql.Date.valueOf(end));
            args.addAll(viewable);
            Long sum = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(count), 0)
                      FROM document_view_counts
                     WHERE day BETWEEN ? AND ?
                       AND document_id IN (%s)
                    """.formatted(in),
                    Long.class,
                    args.toArray());
            viewsThisMonth = sum != null ? sum : 0L;
        }

        return new Kpis(published, pendingCount, viewsThisMonth, avgReliability);
    }

    private List<ResumeItem> loadResume(UUID userId, Set<UUID> viewableSet) {
        List<ResumeItem> raw = jdbc.query("""
                SELECT id, title, updated_at
                  FROM documents
                 WHERE deleted_at IS NULL
                   AND status = 'brouillon'
                   AND updated_by = ?
                 ORDER BY updated_at DESC
                 LIMIT ?
                """,
                (rs, i) -> {
                    Instant updated = rs.getTimestamp("updated_at").toInstant();
                    return new ResumeItem(
                            (UUID) rs.getObject("id"),
                            rs.getString("title"),
                            updated,
                            relativeLabel(updated));
                },
                userId, RESUME_LIMIT * 3);

        List<ResumeItem> out = new ArrayList<>();
        for (ResumeItem item : raw) {
            if (!viewableSet.contains(item.documentId())) {
                continue;
            }
            out.add(item);
            if (out.size() >= RESUME_LIMIT) {
                break;
            }
        }
        return out;
    }

    private List<RecentlyPublishedItem> loadRecentlyPublished(List<UUID> viewable) {
        if (viewable.isEmpty()) {
            return List.of();
        }
        String in = placeholders(viewable.size());
        List<Object> args = new ArrayList<>(viewable);
        args.add(RECENT_PUBLISHED_LIMIT);
        return jdbc.query("""
                SELECT d.id, d.title, s.name AS space_name, d.updated_at
                  FROM documents d
                  JOIN spaces s ON s.id = d.space_id
                 WHERE d.deleted_at IS NULL
                   AND d.status = 'valide'
                   AND d.id IN (%s)
                 ORDER BY d.updated_at DESC
                 LIMIT ?
                """.formatted(in),
                (rs, i) -> new RecentlyPublishedItem(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("space_name"),
                        rs.getTimestamp("updated_at").toInstant()),
                args.toArray());
    }

    private List<PendingApprovalItem> loadPendingApprovals(Jwt jwt, UUID currentUserId) {
        DocumentApprovalService approvals = approvalService.getIfAvailable();
        if (approvals == null) {
            return List.of();
        }
        List<ApprovalView> mine = approvals.listMine(jwt);
        if (mine.isEmpty()) {
            return List.of();
        }

        Set<UUID> requesterIds = new HashSet<>();
        for (ApprovalView v : mine) {
            if (v.requestedBy() != null) {
                requesterIds.add(v.requestedBy());
            }
        }
        Map<UUID, String> names = loadDisplayNames(requesterIds);

        List<PendingApprovalItem> out = new ArrayList<>();
        for (ApprovalView v : mine) {
            Instant sla = v.slaDeadlineAt() != null ? Instant.parse(v.slaDeadlineAt()) : null;
            out.add(new PendingApprovalItem(
                    v.documentId(),
                    v.approvalRequestId(),
                    v.documentTitle(),
                    names.getOrDefault(v.requestedBy(), "—"),
                    slaRemainingLabel(sla)));
        }
        return out;
    }

    private List<TeamActivityItem> loadTeamActivity(UUID currentUserId, Set<UUID> viewableSet) {
        if (viewableSet.isEmpty()) {
            return List.of();
        }
        // Préselect bornée puis filtre viewable (évite N+1 OpenFGA).
        List<ActivityRow> rows = jdbc.query("""
                SELECT ae.id, ae.event_type, ae.actor_user_id, ae.document_id, ae.created_at,
                       u.display_name AS actor_name, d.title AS document_title
                  FROM activity_events ae
                  JOIN users u ON u.id = ae.actor_user_id
                  JOIN documents d ON d.id = ae.document_id
                 WHERE d.deleted_at IS NULL
                 ORDER BY ae.created_at DESC
                 LIMIT 80
                """,
                (rs, i) -> new ActivityRow(
                        rs.getString("event_type"),
                        (UUID) rs.getObject("actor_user_id"),
                        rs.getString("actor_name"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("document_title"),
                        rs.getTimestamp("created_at").toInstant()));

        List<TeamActivityItem> out = new ArrayList<>();
        for (ActivityRow r : rows) {
            if (!viewableSet.contains(r.documentId())) {
                continue;
            }
            boolean you = currentUserId.equals(r.actorUserId());
            out.add(new TeamActivityItem(
                    r.eventType(),
                    you ? "Vous" : r.actorName(),
                    you,
                    r.documentTitle(),
                    r.documentId(),
                    r.createdAt(),
                    relativeLabel(r.createdAt()),
                    actionLabel(r.eventType(), you)));
            if (out.size() >= TEAM_ACTIVITY_LIMIT) {
                break;
            }
        }
        return out;
    }

    private Map<UUID, String> loadDisplayNames(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String in = placeholders(ids.size());
        Map<UUID, String> map = new HashMap<>();
        jdbc.query("""
                SELECT id, display_name FROM users WHERE id IN (%s)
                """.formatted(in),
                rs -> {
                    while (rs.next()) {
                        map.put((UUID) rs.getObject("id"), rs.getString("display_name"));
                    }
                    return null;
                },
                ids.toArray());
        return map;
    }

    static String actionLabel(String eventType, boolean you) {
        return switch (eventType == null ? "" : eventType) {
            case ActivityEventTypes.COMMENT ->
                    you ? "Vous avez commenté" : "a commenté";
            case ActivityEventTypes.EDIT_PROPOSAL ->
                    you ? "Vous avez proposé une modification" : "a proposé une modification";
            case ActivityEventTypes.SUBMISSION ->
                    you ? "Vous avez soumis pour approbation" : "a soumis pour approbation";
            case ActivityEventTypes.PUBLICATION ->
                    you ? "Vous avez publié" : "a publié";
            default -> you ? "Vous avez agi" : "a agi";
        };
    }

    String relativeLabel(Instant when) {
        if (when == null) {
            return "";
        }
        Duration d = Duration.between(when, clock.instant());
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "à l'instant";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "il y a " + minutes + " min";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "il y a " + hours + " h";
        }
        long days = hours / 24;
        if (days < 30) {
            return "il y a " + days + " j";
        }
        long months = days / 30;
        return "il y a " + months + " mois";
    }

    String slaRemainingLabel(Instant deadline) {
        if (deadline == null) {
            return "—";
        }
        Duration d = Duration.between(clock.instant(), deadline);
        if (d.isNegative()) {
            long hours = Math.abs(d.toHours());
            if (hours < 24) {
                return "dépassé de " + Math.max(1, hours) + " h";
            }
            return "dépassé de " + Math.max(1, hours / 24) + " j";
        }
        long hours = d.toHours();
        if (hours < 1) {
            return "< 1 h";
        }
        if (hours < 48) {
            return hours + " h restantes";
        }
        return (hours / 24) + " j restants";
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private record ActivityRow(
            String eventType,
            UUID actorUserId,
            String actorName,
            UUID documentId,
            String documentTitle,
            Instant createdAt
    ) {}
}
