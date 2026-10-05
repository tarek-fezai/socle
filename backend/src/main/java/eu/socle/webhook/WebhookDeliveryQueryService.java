// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lecture seule des {@code webhook_deliveries} pour l'écran Historique des livraisons.
 */
@Service
public class WebhookDeliveryQueryService {

    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;

    public WebhookDeliveryQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DeliveryPage list(UUID endpointId, String status, int offset, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(offset, 0);

        if (status != null && !status.isBlank()) {
            String s = status.trim().toLowerCase();
            if (!List.of("pending", "delivered", "failed", "retrying").contains(s)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status invalide");
            }
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (endpointId != null) {
            where.append(" AND d.endpoint_id = ? ");
            args.add(endpointId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND d.status = ? ");
            args.add(status.trim().toLowerCase());
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM webhook_deliveries d" + where,
                Long.class,
                args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeLimit);
        pageArgs.add(safeOffset);

        List<DeliveryView> items = jdbcTemplate.query("""
                SELECT d.id, d.endpoint_id, e.url AS endpoint_url, d.event_type, d.status,
                       d.attempt_count, d.last_response_code, d.delivered_at, d.created_at
                  FROM webhook_deliveries d
                  JOIN webhook_endpoints e ON e.id = d.endpoint_id
                """ + where + """
                 ORDER BY d.created_at DESC
                 LIMIT ? OFFSET ?
                """,
                (rs, i) -> {
                    Timestamp delivered = rs.getTimestamp("delivered_at");
                    return new DeliveryView(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("endpoint_id"),
                            rs.getString("endpoint_url"),
                            rs.getString("event_type"),
                            rs.getString("status"),
                            rs.getInt("attempt_count"),
                            (Integer) rs.getObject("last_response_code"),
                            delivered != null ? delivered.toInstant() : null,
                            rs.getTimestamp("created_at").toInstant()
                    );
                },
                pageArgs.toArray());

        return new DeliveryPage(items, safeOffset, safeLimit, total != null ? total : 0);
    }

    public record DeliveryView(
            UUID id,
            UUID endpointId,
            String endpointUrl,
            String eventType,
            String status,
            int attemptCount,
            Integer lastResponseCode,
            Instant deliveredAt,
            Instant createdAt
    ) {}

    public record DeliveryPage(List<DeliveryView> items, int offset, int limit, long total) {}
}
