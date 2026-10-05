// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du score de fiabilité documentaire.
 * Les poids de la formule restent des constantes code (pas d'admin dans cette itération).
 */
@ConfigurationProperties(prefix = "socle.reliability")
public class ReliabilityScoreProperties {

    /**
     * Cycle de revue par défaut (jours) lorsque aucune {@code retention_policies}
     * ne correspond à l'espace ou à un tag du document.
     */
    private int defaultReviewCycleDays = ReliabilityScoreDefaults.DEFAULT_REVIEW_CYCLE_DAYS;

    public int getDefaultReviewCycleDays() {
        return defaultReviewCycleDays;
    }

    public void setDefaultReviewCycleDays(int defaultReviewCycleDays) {
        this.defaultReviewCycleDays = defaultReviewCycleDays > 0
                ? defaultReviewCycleDays
                : ReliabilityScoreDefaults.DEFAULT_REVIEW_CYCLE_DAYS;
    }
}
