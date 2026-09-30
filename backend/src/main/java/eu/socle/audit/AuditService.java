package eu.socle.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit append-only ({@code audit_log_events}).
 * INSERT uniquement — jamais UPDATE/DELETE (contraint aussi en base, V5).
 *
 * <p>{@link #recordSync} : échec → exception (événements conformité critiques).<br>
 * {@link #record} : async, échec → log ERROR + métrique {@code socle.audit.write.failures}.
 *
 * <p>Après INSERT réussi : enqueue SIEM async best-effort (jamais bloquant, même pour
 * les événements sync).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final String METRIC_WRITE_FAILURES = "socle.audit.write.failures";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Counter writeFailures;
    private final SiemDeliveryEnqueueService siemEnqueue;

    /**
     * Constructeur Spring — unique candidat d'injection ({@code @Autowired} explicite
     * car un constructeur de test à 3 args existe aussi et ferait échouer le démarrage
     * avec « No default constructor found »).
     */
    @Autowired
    public AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ObjectProvider<SiemDeliveryEnqueueService> siemEnqueue
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, siemEnqueue.getIfAvailable());
    }

    /** Constructeur de test (SIEM optionnel). */
    public AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, (SiemDeliveryEnqueueService) null);
    }

    AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            SiemDeliveryEnqueueService siemEnqueue
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.writeFailures = Counter.builder(METRIC_WRITE_FAILURES)
                .description("Échecs d'écriture async dans audit_log_events")
                .register(meterRegistry);
        this.siemEnqueue = siemEnqueue;
    }

    /**
     * Écriture asynchrone (ex. document.created/updated).
     * En cas d'échec : log ERROR + incrément métrique — ne propage pas.
     */
    @Async
    public void record(
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            String ipAddress
    ) {
        try {
            doInsert(actorId, actorIsSystem, action, resourceType, resourceId, metadata, ipAddress);
        } catch (Exception e) {
            writeFailures.increment();
            log.error("Échec écriture audit_log_events (async) action={} resource={}:{} — métrique {}",
                    action, resourceType, resourceId, METRIC_WRITE_FAILURES, e);
        }
    }

    /**
     * Écriture synchrone fail-fast (access.*, Temporal, chaîne épuisée).
     * En cas d'échec : {@link AuditWriteException} — l'appelant ne doit pas considérer l'action réussie.
     */
    public void recordSync(
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            String ipAddress
    ) {
        try {
            doInsert(actorId, actorIsSystem, action, resourceType, resourceId, metadata, ipAddress);
        } catch (Exception e) {
            log.error("Échec écriture audit_log_events (sync) action={} resource={}:{}",
                    action, resourceType, resourceId, e);
            throw new AuditWriteException(
                    "Impossible d'écrire l'événement d'audit " + action, e);
        }
    }

    private void doInsert(
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            String ipAddress
    ) {
        String metaJson = toJson(metadata);
        Instant now = Instant.now();
        Long auditEventId;
        if (ipAddress == null || ipAddress.isBlank()) {
            auditEventId = jdbcTemplate.queryForObject("""
                    INSERT INTO audit_log_events
                      (actor_id, actor_is_system, action, resource_type, resource_id, metadata, ip_address, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), NULL, ?)
                    RETURNING id
                    """,
                    Long.class,
                    actorId, actorIsSystem, action, resourceType, resourceId, metaJson,
                    java.sql.Timestamp.from(now));
        } else {
            auditEventId = jdbcTemplate.queryForObject("""
                    INSERT INTO audit_log_events
                      (actor_id, actor_is_system, action, resource_type, resource_id, metadata, ip_address, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS inet), ?)
                    RETURNING id
                    """,
                    Long.class,
                    actorId, actorIsSystem, action, resourceType, resourceId, metaJson, ipAddress,
                    java.sql.Timestamp.from(now));
        }

        if (auditEventId != null && siemEnqueue != null) {
            siemEnqueue.enqueueAfterAudit(
                    auditEventId,
                    actorId,
                    actorIsSystem,
                    action,
                    resourceType,
                    resourceId,
                    metadata,
                    now
            );
        }
    }

    private String toJson(Map<String, Object> metadata) {
        Map<String, Object> safe = sanitizeMetadata(metadata);
        if (safe.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException e) {
            log.warn("metadata non sérialisable pour audit: {}", e.getMessage());
            return "{\"error\":\"metadata_serialization_failed\"}";
        }
    }

    /**
     * Périmètre metadata-only : jamais de corps TipTap / snapshot dans le journal
     * (l'auditeur n'est pas un super-lecteur OpenFGA). Voir {@code docs/audit-logging.md}.
     */
    static Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(metadata);
        copy.remove("body");
        copy.remove("bodySnapshot");
        copy.remove("content");
        copy.remove("resolvedBody");
        copy.remove("previousBody");
        return copy;
    }

    /** Échec d'INSERT sync — le grant/revoke / activity ne doit pas être considéré réussi. */
    public static class AuditWriteException extends RuntimeException {
        public AuditWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
