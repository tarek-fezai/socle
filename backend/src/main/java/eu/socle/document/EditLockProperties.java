// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Soft-lock advisory — TTL court. Voir {@code docs/editing-concurrency.md}.
 */
@Component
@ConfigurationProperties(prefix = "socle.edit-lock")
public class EditLockProperties {

    /** Intervalle heartbeat côté client (secondes) — informatif / doc. */
    private int heartbeatSeconds = 15;

    /** Expiration sans heartbeat (secondes). Défaut = 3 × heartbeat. */
    private int ttlSeconds = 45;

    @PostConstruct
    void validate() {
        if (heartbeatSeconds <= 0 || ttlSeconds <= 0) {
            throw new IllegalStateException("socle.edit-lock heartbeat/ttl doivent être > 0");
        }
        if (ttlSeconds < heartbeatSeconds) {
            throw new IllegalStateException(
                    "socle.edit-lock.ttlSeconds doit être >= heartbeatSeconds");
        }
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
