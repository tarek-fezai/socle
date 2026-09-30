package eu.socle.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Seuil de fraîcheur à l'échelle de l'<strong>instance</strong> (pas par espace).
 * Lu au démarrage — fail-fast si absurde. Voir {@code docs/content-staleness.md}.
 */
@Component
@ConfigurationProperties(prefix = "socle.staleness")
public class StalenessProperties {

    /** Jours sans écriture de contenu avant badge stale. Défaut documenté : 90. */
    private int thresholdDays = 90;

    @PostConstruct
    void validate() {
        if (thresholdDays <= 0) {
            throw new IllegalStateException(
                    "socle.staleness.thresholdDays doit être > 0 (reçu: " + thresholdDays + ")");
        }
        if (thresholdDays > 3650) {
            throw new IllegalStateException(
                    "socle.staleness.thresholdDays absurde (> 10 ans) : " + thresholdDays);
        }
    }

    public int getThresholdDays() {
        return thresholdDays;
    }

    public void setThresholdDays(int thresholdDays) {
        this.thresholdDays = thresholdDays;
    }
}
