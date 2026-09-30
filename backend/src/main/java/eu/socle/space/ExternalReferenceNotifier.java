package eu.socle.space;

import eu.socle.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notifie les owners de l'espace <em>cible</em> à la première référence externe
 * provenant d'un autre espace (granularité : paire source→cible).
 * Déclenché depuis {@link eu.socle.document.DocumentLinkService}
 * à l'indexation d'un lien inter-espace.
 */
@Service
public class ExternalReferenceNotifier implements ExternalReferenceNotify {

    public static final String NOTIFICATION_TYPE = "external_reference_first";

    private static final Logger log = LoggerFactory.getLogger(ExternalReferenceNotifier.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;

    public ExternalReferenceNotifier(JdbcTemplate jdbc, NotificationService notificationService) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
    }

    /**
     * Idempotent : une seule vague de notifs par paire ({@code sourceSpaceId} → {@code targetSpaceId}).
     */
    @Override
    @Transactional
    public void notifyOwnersOnFirstPair(UUID sourceSpaceId, UUID targetSpaceId) {
        if (sourceSpaceId == null || targetSpaceId == null || sourceSpaceId.equals(targetSpaceId)) {
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO space_external_ref_notices (source_space_id, target_space_id, notified_at)
                VALUES (?, ?, now())
                ON CONFLICT DO NOTHING
                """,
                sourceSpaceId, targetSpaceId);
        if (inserted == 0) {
            return;
        }

        String sourceName = spaceName(sourceSpaceId);
        String targetName = spaceName(targetSpaceId);
        List<UUID> owners = jdbc.query(
                "SELECT user_id FROM space_owners WHERE space_id = ?",
                (rs, i) -> (UUID) rs.getObject("user_id"),
                targetSpaceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("source_space_id", sourceSpaceId.toString());
        payload.put("target_space_id", targetSpaceId.toString());
        payload.put("source_space_name", sourceName);
        payload.put("target_space_name", targetName);
        payload.put("message",
                "Un document de l'espace « " + sourceName
                        + " » référence désormais (transclusion) un contenu de « "
                        + targetName + " ».");

        for (UUID ownerId : owners) {
            try {
                notificationService.create(ownerId, NOTIFICATION_TYPE, payload);
            } catch (Exception e) {
                log.error("Échec notification external_reference owner={} targetSpace={}",
                        ownerId, targetSpaceId, e);
                throw e;
            }
        }
    }

    private String spaceName(UUID spaceId) {
        String name = jdbc.query(
                "SELECT name FROM spaces WHERE id = ?",
                rs -> rs.next() ? rs.getString("name") : null,
                spaceId);
        return name != null ? name : spaceId.toString();
    }
}
