// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.home;

import eu.socle.activity.ActivityEventTypes;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.AuthorizationService.ReadableScope;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agrégat Accueil.
 *
 * <p><strong>Indicateurs</strong> (publiés, vues, fiabilité) : agrégats SQL sur la
 * présélection de lisibilité ({@link AuthorizationService#READABLE_PREDICATE} via
 * {@link AuthorizationService#readableScope}) — <em>sans</em> BatchCheck OpenFGA.
 * Seuls des nombres sont exposés ; une divergence colonne / tuples OpenFGA est
 * détectée par {@code visibility-drift} (voir {@code docs/privacy.md}).
 *
 * <p><strong>Listes</strong> (Reprendre, Récemment publié, Activité) : SQL avec la
 * même présélection et {@code LIMIT} sur-dimensionné (×{@link #CANDIDATE_MULTIPLIER}),
 * puis {@link AuthorizationService#filterByDocumentViewer} sur ces seuls candidats.
 * Aucune information d'un document refusé ne sort.
 *
 * <p>Ne pas utiliser {@link eu.socle.authz.DocumentScope#global()} ici.
 */
@Service
public class HomeService {

    static final int RESUME_LIMIT = 5;
    static final int RECENT_PUBLISHED_LIMIT = 8;
    static final int TEAM_ACTIVITY_LIMIT = 10;
    /** Sur-sélection SQL avant BatchCheck (bornée, indépendante du volume instance). */
    static final int CANDIDATE_MULTIPLIER = 3;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final IdentityClaimsMapper claimsMapper;
    private final ObjectProvider<DocumentApprovalService> approvalService;
    private final Clock clock;

    public HomeService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            IdentityClaimsMapper claimsMapper,
            ObjectProvider<DocumentApprovalService> approvalService,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.claimsMapper = claimsMapper;
        this.approvalService = approvalService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public HomeResponse getHome(Jwt jwt) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        ReadableScope readable = authorizationService.readableScope(user.getId());

        String greeting = resolveGreeting(jwt, user);
        Kpis kpis = computeKpis(jwt, readable);
        List<ResumeItem> resume = loadResume(user.getId(), readable);
        List<RecentlyPublishedItem> recentlyPublished = loadRecentlyPublished(user.getId(), readable);
        List<PendingApprovalItem> pending = loadPendingApprovals(jwt, user.getId());
        List<TeamActivityItem> activity = loadTeamActivity(user.getId(), readable);

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
        return display.trim().split("\\s+")[0];
    }

    /**
     * KPIs numériques uniquement — présélection SQL, pas de BatchCheck.
     * Peut légèrement diverger d'OpenFGA tant que {@code visibility-drift} n'est pas nul.
     */
    private Kpis computeKpis(Jwt jwt, ReadableScope readable) {
        Map<String, Object> row = jdbc.query(
                """
                SELECT COUNT(*) FILTER (WHERE d.status = 'valide') AS published,
                       AVG(d.reliability_score) FILTER (
                         WHERE d.status = 'valide' AND d.reliability_score IS NOT NULL
                       ) AS avg_rel
                  FROM documents d
                 WHERE d.deleted_at IS NULL
                   AND %s
                """.formatted(AuthorizationService.READABLE_PREDICATE),
                ps -> authorizationService.bindReadable(ps, 1, readable),
                rs -> {
                    rs.next();
                    Map<String, Object> m = new HashMap<>();
                    m.put("published", rs.getLong("published"));
                    m.put("avg_rel", rs.getObject("avg_rel"));
                    return m;
                });

        long published = ((Number) row.get("published")).longValue();
        Integer avgReliability = null;
        Object avg = row.get("avg_rel");
        if (avg instanceof BigDecimal bd) {
            avgReliability = bd.setScale(0, RoundingMode.HALF_UP).intValue();
        } else if (avg instanceof Number n) {
            avgReliability = (int) Math.round(n.doubleValue());
        }

        long pendingCount = 0;
        DocumentApprovalService approvals = approvalService.getIfAvailable();
        if (approvals != null) {
            pendingCount = approvals.listMine(jwt).size();
        }

        YearMonth ym = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        Long views = jdbc.query(
                """
                SELECT COALESCE(SUM(c.count), 0)
                  FROM document_view_counts c
                  JOIN documents d ON d.id = c.document_id
                 WHERE d.deleted_at IS NULL
                   AND c.day BETWEEN ? AND ?
                   AND %s
                """.formatted(AuthorizationService.READABLE_PREDICATE),
                ps -> {
                    ps.setDate(1, java.sql.Date.valueOf(start));
                    ps.setDate(2, java.sql.Date.valueOf(end));
                    authorizationService.bindReadable(ps, 3, readable);
                },
                rs -> {
                    rs.next();
                    return rs.getLong(1);
                });
        long viewsThisMonth = views != null ? views : 0L;

        return new Kpis(published, pendingCount, viewsThisMonth, avgReliability);
    }

    private List<ResumeItem> loadResume(UUID userId, ReadableScope readable) {
        int candidateLimit = RESUME_LIMIT * CANDIDATE_MULTIPLIER;
        List<ResumeItem> raw = jdbc.query(
                """
                SELECT d.id, d.title, d.updated_at
                  FROM documents d
                 WHERE d.deleted_at IS NULL
                   AND d.status = 'brouillon'
                   AND d.updated_by = ?
                   AND %s
                 ORDER BY d.updated_at DESC
                 LIMIT ?
                """.formatted(AuthorizationService.READABLE_PREDICATE),
                ps -> {
                    ps.setObject(1, userId);
                    int idx = authorizationService.bindReadable(ps, 2, readable);
                    ps.setInt(idx, candidateLimit);
                },
                (rs, i) -> {
                    Instant updated = rs.getTimestamp("updated_at").toInstant();
                    return new ResumeItem(
                            (UUID) rs.getObject("id"),
                            rs.getString("title"),
                            updated,
                            relativeLabel(updated));
                });

        return keepAllowedInOrder(
                raw,
                ResumeItem::documentId,
                userId,
                "home-resume",
                RESUME_LIMIT);
    }

    private List<RecentlyPublishedItem> loadRecentlyPublished(UUID userId, ReadableScope readable) {
        int candidateLimit = RECENT_PUBLISHED_LIMIT * CANDIDATE_MULTIPLIER;
        List<RecentlyPublishedItem> raw = jdbc.query(
                """
                SELECT d.id, d.title, s.name AS space_name, d.updated_at
                  FROM documents d
                  JOIN spaces s ON s.id = d.space_id
                 WHERE d.deleted_at IS NULL
                   AND d.status = 'valide'
                   AND %s
                 ORDER BY d.updated_at DESC
                 LIMIT ?
                """.formatted(AuthorizationService.READABLE_PREDICATE),
                ps -> {
                    int idx = authorizationService.bindReadable(ps, 1, readable);
                    ps.setInt(idx, candidateLimit);
                },
                (rs, i) -> new RecentlyPublishedItem(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("space_name"),
                        rs.getTimestamp("updated_at").toInstant()));

        return keepAllowedInOrder(
                raw,
                RecentlyPublishedItem::documentId,
                userId,
                "home-recent-published",
                RECENT_PUBLISHED_LIMIT);
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

    private List<TeamActivityItem> loadTeamActivity(UUID currentUserId, ReadableScope readable) {
        int candidateLimit = TEAM_ACTIVITY_LIMIT * CANDIDATE_MULTIPLIER;
        List<ActivityRow> rows = jdbc.query(
                """
                SELECT ae.id, ae.event_type, ae.actor_user_id, ae.document_id, ae.created_at,
                       u.display_name AS actor_name, d.title AS document_title
                  FROM activity_events ae
                  JOIN users u ON u.id = ae.actor_user_id
                  JOIN documents d ON d.id = ae.document_id
                 WHERE d.deleted_at IS NULL
                   AND %s
                 ORDER BY ae.created_at DESC
                 LIMIT ?
                """.formatted(AuthorizationService.READABLE_PREDICATE),
                ps -> {
                    int idx = authorizationService.bindReadable(ps, 1, readable);
                    ps.setInt(idx, candidateLimit);
                },
                (rs, i) -> new ActivityRow(
                        rs.getString("event_type"),
                        (UUID) rs.getObject("actor_user_id"),
                        rs.getString("actor_name"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("document_title"),
                        rs.getTimestamp("created_at").toInstant()));

        LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
        for (ActivityRow r : rows) {
            candidateIds.add(r.documentId());
        }
        Set<UUID> allowed = new HashSet<>(authorizationService.filterByDocumentViewer(
                currentUserId, candidateIds, "home-activity"));

        List<TeamActivityItem> out = new ArrayList<>();
        for (ActivityRow r : rows) {
            if (!allowed.contains(r.documentId())) {
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

    private <T> List<T> keepAllowedInOrder(
            List<T> raw,
            java.util.function.Function<T, UUID> idFn,
            UUID userId,
            String scopeLabel,
            int limit
    ) {
        if (raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
        for (T item : raw) {
            candidateIds.add(idFn.apply(item));
        }
        Set<UUID> allowed = new HashSet<>(authorizationService.filterByDocumentViewer(
                userId, candidateIds, scopeLabel));
        List<T> out = new ArrayList<>();
        for (T item : raw) {
            if (!allowed.contains(idFn.apply(item))) {
                continue;
            }
            out.add(item);
            if (out.size() >= limit) {
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
