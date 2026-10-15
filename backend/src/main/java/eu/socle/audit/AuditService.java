// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit append-only ({@code audit_log_events}).
 * INSERT uniquement — jamais UPDATE/DELETE (contraint aussi en base, V5).
 *
 * <p>{@link #recordSync} : échec → exception (événements conformité critiques).<br>
 * {@link #record} : insertion asynchrone après commit de la transaction appelante
 * (rien si elle est annulée). Sans transaction : insertion asynchrone immédiate.
 * Échec → log ERROR + métrique {@code socle.audit.write.failures}.
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
    private final ObjectProvider<AuditService> self;

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
            ObjectProvider<SiemDeliveryEnqueueService> siemEnqueue,
            ObjectProvider<AuditService> self
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, siemEnqueue.getIfAvailable(), self);
    }

    /** Constructeur de test (SIEM optionnel, pas de proxy async). */
    public AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, (SiemDeliveryEnqueueService) null, null);
    }

    AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            SiemDeliveryEnqueueService siemEnqueue
    ) {
        this(jdbcTemplate, objectMapper, meterRegistry, siemEnqueue, null);
    }

    private AuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            SiemDeliveryEnqueueService siemEnqueue,
            ObjectProvider<AuditService> self
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.writeFailures = Counter.builder(METRIC_WRITE_FAILURES)
                .description("Échecs d'écriture async dans audit_log_events")
                .register(meterRegistry);
        this.siemEnqueue = siemEnqueue;
        this.self = self;
    }

    /**
     * Écriture asynchrone (ex. document.created/updated).
     * Transaction Spring active : l'INSERT part dans {@code afterCommit} uniquement.
     * Annulation : aucune ligne. Sans transaction : comportement immédiat.
     * En cas d'échec : log ERROR + incrément métrique — ne propage pas.
     */
    public void record(
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            String ipAddress
    ) {
        Map<String, Object> snapshot = snapshot(metadata);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchAsync(actorId, actorIsSystem, action, resourceType, resourceId, snapshot, ipAddress);
                }
            });
            return;
        }
        dispatchAsync(actorId, actorIsSystem, action, resourceType, resourceId, snapshot, ipAddress);
    }

    /**
     * INSERT async. Public pour le proxy Spring ({@code @Async} ignoré sur un appel interne).
     */
    @Async
    public void insertAsync(
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

    private void dispatchAsync(
            UUID actorId,
            boolean actorIsSystem,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> metadata,
            String ipAddress
    ) {
        AuditService proxy = self == null ? null : self.getIfAvailable();
        if (proxy != null && proxy != this) {
            proxy.insertAsync(actorId, actorIsSystem, action, resourceType, resourceId, metadata, ipAddress);
            return;
        }
        insertAsync(actorId, actorIsSystem, action, resourceType, resourceId, metadata, ipAddress);
    }

    private static Map<String, Object> snapshot(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(metadata);
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
        metadata = AuthMethodContext.enrich(metadata);
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
