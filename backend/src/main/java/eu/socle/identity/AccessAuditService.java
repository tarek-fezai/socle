// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audit de la politique d'accès. Ne reçoit jamais le token : seulement issuer, subject, motif.
 *
 * <ul>
 *   <li>{@code auth.access_denied} : au plus 1 entrée par (utilisateur, motif) et par 10 min
 *       (mémoire locale à l'instance) ;</li>
 *   <li>{@code auth.access_granted} : appelé uniquement à la première connexion réussie.</li>
 * </ul>
 */
@Service
public class AccessAuditService {

    public static final Duration DENIED_RATE_LIMIT = Duration.ofMinutes(10);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final AuditService auditService;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastDenied = new ConcurrentHashMap<>();

    @Autowired
    public AccessAuditService(AuditService auditService) {
        this(auditService, Clock.systemUTC());
    }

    public AccessAuditService(AuditService auditService, Clock clock) {
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * @param userId compte existant si connu (sinon {@code null} — aucun compte n'est créé)
     * @return {@code true} si une entrée a été écrite, {@code false} si dédupliquée
     */
    public boolean recordDenied(String issuer, String subject, UUID userId, AccessDeniedReason reason) {
        Instant now = clock.instant();
        String key = issuer + '\n' + subject + '\n' + reason.code();
        if (lastDenied.size() >= CLEANUP_THRESHOLD) {
            lastDenied.entrySet().removeIf(e -> !now.isBefore(e.getValue().plus(DENIED_RATE_LIMIT)));
        }
        boolean[] write = {false};
        lastDenied.compute(key, (k, previous) -> {
            if (previous == null || !now.isBefore(previous.plus(DENIED_RATE_LIMIT))) {
                write[0] = true;
                return now;
            }
            return previous;
        });
        if (!write[0]) {
            return false;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", reason.code());
        meta.put("issuer", issuer);
        meta.put("subject", subject);
        auditService.record(null, false, AuditActions.AUTH_ACCESS_DENIED, "user", userId, meta, null);
        return true;
    }

    public void recordGranted(UUID userId, String issuer, String subject, IdentityProperties.AccessMode mode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("issuer", issuer);
        meta.put("subject", subject);
        meta.put("mode", mode.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        auditService.record(userId, false, AuditActions.AUTH_ACCESS_GRANTED, "user", userId, meta, null);
    }
}
