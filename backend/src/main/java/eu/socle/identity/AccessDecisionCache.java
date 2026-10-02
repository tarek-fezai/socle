// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache mémoire des décisions d'accès par {@code (issuer, subject)} — TTL max 60 s.
 * Invalidé explicitement lors d'un {@code disable} / {@code enable} administrateur.
 * (Par instance : un autre nœud applique la décision au plus tard après expiration du TTL.)
 */
@Component
public class AccessDecisionCache {

    public static final Duration TTL = Duration.ofSeconds(60);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Entry(AccessPolicyService.Decision decision, Instant expiresAt) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public AccessDecisionCache() {
        this(Clock.systemUTC());
    }

    public AccessDecisionCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<AccessPolicyService.Decision> get(String issuer, String subject) {
        String key = key(issuer, subject);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.decision());
    }

    public void put(String issuer, String subject, AccessPolicyService.Decision decision) {
        Instant now = clock.instant();
        if (entries.size() >= CLEANUP_THRESHOLD) {
            entries.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
        }
        entries.put(key(issuer, subject), new Entry(decision, now.plus(TTL)));
    }

    public void invalidate(String issuer, String subject) {
        entries.remove(key(issuer, subject));
    }

    public void invalidateAll() {
        entries.clear();
    }

    private static String key(String issuer, String subject) {
        return issuer + '\n' + subject;
    }
}
