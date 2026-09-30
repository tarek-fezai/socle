package eu.socle.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recalcule quotidiennement la composante fraîcheur des documents {@code valide}
 * dont {@code reliability_computed_at} a plus de 24h.
 */
@Component
public class ReliabilityScoreScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityScoreScheduler.class);

    private final ReliabilityScoreService reliabilityScoreService;

    public ReliabilityScoreScheduler(ReliabilityScoreService reliabilityScoreService) {
        this.reliabilityScoreService = reliabilityScoreService;
    }

    /** Tous les jours à 03:15 UTC. */
    @Scheduled(cron = "0 15 3 * * *")
    public void refreshStaleScores() {
        int n = reliabilityScoreService.recalculateStaleValideDocuments();
        if (n > 0) {
            log.info("reliability_score : {} document(s) valide recalculé(s) (job périodique)", n);
        }
    }
}
