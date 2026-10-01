// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "socle")
public record SocleProperties(
        Temporal temporal,
        OpenFga openfga,
        Cors cors,
        Instance instance
) {
    public record Temporal(String target, String namespace) {}

    /**
     * Identité d'affichage de l'instance. Jamais hardcodée côté produit —
     * configurable via {@code socle.instance.display-name}.
     */
    public record Instance(String displayName) {
        public String effectiveDisplayName() {
            return displayName == null || displayName.isBlank() ? "Socle" : displayName;
        }
    }

    /**
     * @param listObjectsMaxResults plafond OpenFGA ListObjects (défaut 1000)
     * @param visibilityMigrationOnStartup si true, migre owner→editor au boot
     * @param maxChecksPerBatchCheck taille max d'un BatchCheck (défaut 50)
     * @param batchCheckParallelism parallélisme borné des lots BatchCheck (défaut 4)
     * @param documentCheckWarnThreshold WARN si plus de N checks viewer par appel (défaut 500)
     */
    public record OpenFga(
            String apiUrl,
            String storeId,
            String authorizationModelId,
            Integer listObjectsMaxResults,
            Boolean visibilityMigrationOnStartup,
            Integer maxChecksPerBatchCheck,
            Integer batchCheckParallelism,
            Integer documentCheckWarnThreshold
    ) {
        public int effectiveListObjectsMaxResults() {
            return listObjectsMaxResults == null || listObjectsMaxResults <= 0
                    ? 1000
                    : listObjectsMaxResults;
        }

        public boolean visibilityMigrationOnStartupEnabled() {
            return Boolean.TRUE.equals(visibilityMigrationOnStartup);
        }

        public int effectiveMaxChecksPerBatchCheck() {
            return maxChecksPerBatchCheck == null || maxChecksPerBatchCheck <= 0
                    ? 50
                    : maxChecksPerBatchCheck;
        }

        public int effectiveBatchCheckParallelism() {
            return batchCheckParallelism == null || batchCheckParallelism <= 0
                    ? 4
                    : batchCheckParallelism;
        }

        public int effectiveDocumentCheckWarnThreshold() {
            return documentCheckWarnThreshold == null || documentCheckWarnThreshold <= 0
                    ? 500
                    : documentCheckWarnThreshold;
        }
    }

    public record Cors(String allowedOrigins) {}
}
