// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox SIEM : après un INSERT audit réussi, crée des {@code siem_deliveries}
 * pour chaque connecteur {@code connected}. Toujours best-effort / async —
 * n'échoue jamais l'action métier ni l'écriture audit.
 */
@Service
public class SiemDeliveryEnqueueService {

    private static final Logger log = LoggerFactory.getLogger(SiemDeliveryEnqueueService.class);

    public static final String METRIC_ENQUEUE_FAILURES = "socle.siem.enqueue.failures";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SiemPayloadFormatter formatter;
    private final Counter enqueueFailures;

    public SiemDeliveryEnqueueService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SiemPayloadFormatter formatter,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
        this.enqueueFailures = Counter.builder(METRIC_ENQUEUE_FAILURES)
                .description("Échecs d'enqueue siem_deliveries après audit")
                .register(meterRegistry);
    }

    @Async
    public void enqueueAfterAudit(
            long auditEventId,
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
        try {
            doEnqueue(auditEventId, actorId, actorIsSystem, action, resourceType, resourceId, metadata, createdAt);
        } catch (Exception e) {
            enqueueFailures.increment();
            log.error("Échec enqueue SIEM pour audit_event_id={} action={} — métrique {}",
                    auditEventId, action, METRIC_ENQUEUE_FAILURES, e);
        }
    }

    /** Visible pour tests unitaires (synchrone). */
    int doEnqueue(
            long auditEventId,
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
        List<ConnectorRow> connectors = jdbcTemplate.query(
                """
                SELECT id, provider, config::text AS config
                  FROM siem_connectors
                 WHERE status = 'connected'
                """,
                (rs, i) -> new ConnectorRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("provider"),
                        rs.getString("config")
                )
        );
        if (connectors.isEmpty()) {
            return 0;
        }

        Instant at = createdAt != null ? createdAt : Instant.now();
        int created = 0;
        for (ConnectorRow c : connectors) {
            var cfg = SiemPayloadFormatter.ConnectorConfig.fromJson(objectMapper, c.config());
            String payload = formatter.format(
                    c.provider(),
                    cfg.format(),
                    auditEventId,
                    actorId,
                    actorIsSystem,
                    action,
                    resourceType,
                    resourceId,
                    metadata,
                    at
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO siem_deliveries
                      (id, connector_id, audit_event_id, event_type, payload, status, attempt_count, created_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'pending', 0, ?)
                    """,
                    UUID.randomUUID(),
                    c.id(),
                    auditEventId,
                    action,
                    payload,
                    Timestamp.from(at)
            );
            created++;
        }
        return created;
    }

    record ConnectorRow(UUID id, String provider, String config) {}
}
