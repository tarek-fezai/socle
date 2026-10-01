// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

/**
 * Constantes nommées du score de fiabilité (pas de valeurs magiques inline).
 * Les poids ne sont pas exposés en configuration utilisateur.
 */
public final class ReliabilityScoreDefaults {

    private ReliabilityScoreDefaults() {}

    /** Cycle de revue (jours) si aucune politique de rétention ne s'applique. */
    public static final int DEFAULT_REVIEW_CYCLE_DAYS = 365;

    public static final double WEIGHT_FRESHNESS = 0.4;
    public static final double WEIGHT_RESOLUTION = 0.3;
    public static final double WEIGHT_ATTESTATION = 0.3;

    /** Âge max du calcul avant qu'un document {@code valide} soit recalculé par le job. */
    public static final int STALE_AFTER_HOURS = 24;
}
