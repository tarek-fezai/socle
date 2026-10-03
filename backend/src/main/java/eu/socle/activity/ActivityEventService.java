// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Écriture / purge des {@code activity_events} (fil home).
 * Les lectures filtrées OpenFGA sont dans {@code HomeService}.
 */
@Service
public class ActivityEventService {

    private static final Logger log = LoggerFactory.getLogger(ActivityEventService.class);
    static final int RETENTION_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ActivityEventService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void record(
            String eventType,
            UUID actorUserId,
            UUID documentId,
            UUID spaceId,
            Map<String, Object> payload
    ) {
        if (eventType == null || eventType.isBlank() || actorUserId == null || documentId == null) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("activity_events payload JSON: {}", e.getMessage());
            json = "{}";
        }
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO activity_events
                  (id, event_type, actor_user_id, document_id, space_id, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                eventType.trim(),
                actorUserId,
                documentId,
                spaceId,
                json,
                Timestamp.from(now));
    }

    /** Supprime les événements plus anciens que {@link #RETENTION_DAYS} jours. */
    @Transactional
    public int purgeOlderThanRetention() {
        Instant cutoff = clock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int n = jdbc.update(
                "DELETE FROM activity_events WHERE created_at < ?",
                Timestamp.from(cutoff));
        if (n > 0) {
            log.info("activity_events : {} ligne(s) purgée(s) (> {} j)", n, RETENTION_DAYS);
        }
        return n;
    }
}
