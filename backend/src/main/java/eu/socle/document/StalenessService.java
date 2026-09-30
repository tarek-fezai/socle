package eu.socle.document;

import eu.socle.storage.DocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Fraîcheur dérivée à la lecture — pas d'état stocké / job périodique.
 */
@Service
public class StalenessService {

    private final DocumentStore documentStore;
    private final StalenessProperties properties;
    private final Clock clock;

    @Autowired
    public StalenessService(DocumentStore documentStore, StalenessProperties properties) {
        this(documentStore, properties, Clock.systemUTC());
    }

    StalenessService(DocumentStore documentStore, StalenessProperties properties, Clock clock) {
        this.documentStore = documentStore;
        this.properties = properties;
        this.clock = clock;
    }

    public Instant contentModifiedAt(UUID documentId, Instant documentCreatedAt) {
        return documentStore.lastContentModifiedAt(documentId, documentCreatedAt);
    }

    public boolean isStale(UUID documentId, Instant documentCreatedAt) {
        Instant modified = contentModifiedAt(documentId, documentCreatedAt);
        return isStaleSince(modified);
    }

    public boolean isStaleSince(Instant contentModifiedAt) {
        if (contentModifiedAt == null) {
            return true;
        }
        Duration age = Duration.between(contentModifiedAt, clock.instant());
        return age.toDays() >= properties.getThresholdDays();
    }

    public long ageDays(Instant contentModifiedAt) {
        if (contentModifiedAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Duration.between(contentModifiedAt, clock.instant()).toDays());
    }

    public int thresholdDays() {
        return properties.getThresholdDays();
    }

    public Freshness freshness(UUID documentId, Instant documentCreatedAt) {
        Instant modified = contentModifiedAt(documentId, documentCreatedAt);
        return new Freshness(
                modified,
                isStaleSince(modified),
                properties.getThresholdDays(),
                ageDays(modified)
        );
    }

    public record Freshness(
            Instant contentModifiedAt,
            boolean stale,
            int thresholdDays,
            long ageDays
    ) {}
}
