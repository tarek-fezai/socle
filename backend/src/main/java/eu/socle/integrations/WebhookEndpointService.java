// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.integrations;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditActorResolver;
import eu.socle.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD {@code webhook_endpoints}. Le secret clair n'est renvoyé qu'à la création ;
 * seule {@code secret_hash} (SHA-256) est persistée.
 */
@Service
public class WebhookEndpointService {

    private static final Set<String> STATUSES = Set.of("active", "disabled");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final AuditActorResolver auditActorResolver;

    public WebhookEndpointService(
            JdbcTemplate jdbc,
            AuditService auditService,
            AuditActorResolver auditActorResolver
    ) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.auditActorResolver = auditActorResolver;
    }

    public List<EndpointView> list() {
        return jdbc.query(
                """
                SELECT id, url, subscribed_events, status, created_at
                  FROM webhook_endpoints
                 ORDER BY created_at DESC
                """,
                (rs, i) -> mapView(rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getArray("subscribed_events"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")));
    }

    public EndpointView get(UUID id) {
        List<EndpointView> rows = jdbc.query(
                """
                SELECT id, url, subscribed_events, status, created_at
                  FROM webhook_endpoints WHERE id = ?
                """,
                (rs, i) -> mapView(rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getArray("subscribed_events"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint introuvable");
        }
        return rows.getFirst();
    }

    /** @return vue + secret clair une seule fois */
    public CreateResult create(String url, List<String> subscribedEvents, String status) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url requise");
        }
        List<String> events = normalizeEvents(subscribedEvents);
        String st = normalizeStatus(status != null ? status : "active");
        String plaintext = SecretSupport.generateWebhookSecret();
        String hash = SecretSupport.sha256Hex(plaintext);
        UUID id = UUID.randomUUID();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO webhook_endpoints (id, url, secret_hash, subscribed_events, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setString(2, url.trim());
            ps.setString(3, hash);
            Array arr = con.createArrayOf("text", events.toArray());
            ps.setArray(4, arr);
            ps.setString(5, st);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            return ps;
        });
        EndpointView view = get(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WEBHOOK_ENDPOINT_CREATED,
                "webhook_endpoint",
                id,
                Map.of(
                        "url", view.url(),
                        "status", view.status(),
                        "eventCount", view.subscribedEvents().size()
                ),
                null);
        return new CreateResult(view, plaintext, SecretSupport.prefixOf(plaintext));
    }

    public EndpointView update(UUID id, String url, List<String> subscribedEvents, String status) {
        EndpointView existing = get(id);
        String newUrl = url != null && !url.isBlank() ? url.trim() : existing.url();
        List<String> events = subscribedEvents != null
                ? normalizeEvents(subscribedEvents)
                : existing.subscribedEvents();
        String st = status != null && !status.isBlank()
                ? normalizeStatus(status)
                : existing.status();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE webhook_endpoints
                       SET url = ?, subscribed_events = ?, status = ?
                     WHERE id = ?
                    """);
            ps.setString(1, newUrl);
            Array arr = con.createArrayOf("text", events.toArray());
            ps.setArray(2, arr);
            ps.setString(3, st);
            ps.setObject(4, id);
            return ps;
        });
        EndpointView updated = get(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WEBHOOK_ENDPOINT_UPDATED,
                "webhook_endpoint",
                id,
                Map.of(
                        "url", updated.url(),
                        "status", updated.status(),
                        "eventCount", updated.subscribedEvents().size()
                ),
                null);
        return updated;
    }

    public void delete(UUID id) {
        EndpointView existing = get(id);
        int n = jdbc.update("DELETE FROM webhook_endpoints WHERE id = ?", id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint introuvable");
        }
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WEBHOOK_ENDPOINT_DELETED,
                "webhook_endpoint",
                id,
                Map.of("url", existing.url()),
                null);
    }

    private static List<String> normalizeEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subscribed_events requis");
        }
        return events.stream()
                .map(e -> e == null ? "" : e.trim())
                .filter(e -> !e.isEmpty())
                .distinct()
                .toList();
    }

    private static String normalizeStatus(String status) {
        String s = status.trim().toLowerCase();
        if (!STATUSES.contains(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status invalide (active|disabled)");
        }
        return s;
    }

    private static EndpointView mapView(
            UUID id, String url, Array eventsArr, String status, Timestamp createdAt
    ) {
        List<String> events = List.of();
        if (eventsArr != null) {
            try {
                Object raw = eventsArr.getArray();
                if (raw instanceof String[] sa) {
                    events = Arrays.asList(sa);
                } else if (raw instanceof Object[] oa) {
                    events = Arrays.stream(oa).map(String::valueOf).toList();
                }
            } catch (java.sql.SQLException e) {
                events = List.of();
            }
        }
        return new EndpointView(
                id,
                url,
                events,
                status,
                createdAt != null ? createdAt.toInstant() : null
        );
    }

    public record EndpointView(
            UUID id,
            String url,
            List<String> subscribedEvents,
            String status,
            Instant createdAt
    ) {}

    public record CreateResult(EndpointView endpoint, String secret, String secretPrefix) {}
}
