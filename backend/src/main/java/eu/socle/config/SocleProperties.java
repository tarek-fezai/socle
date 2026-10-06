// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "socle")
public record SocleProperties(
        Temporal temporal,
        OpenFga openfga,
        Cors cors,
        Instance instance,
        Folders folders
) {
    public record Temporal(String target, String namespace) {}

    /**
     * Identité d'affichage de l'instance. Jamais hardcodée côté produit —
     * configurable via {@code socle.instance.display-name}.
     */
    public record Instance(String displayName, String dataResidenceLabel, String publicBaseUrl) {
        public String effectiveDisplayName() {
            return displayName == null || displayName.isBlank() ? "Socle" : displayName;
        }

        /**
         * Libellé de résidence des données (ex. « Paris, France — hébergement client »),
         * {@code SOCLE_DATA_RESIDENCE_LABEL}. Jamais déduit ni codé en dur : {@code null} si non configuré.
         */
        public String effectiveDataResidenceLabel() {
            return dataResidenceLabel == null || dataResidenceLabel.isBlank() ? null : dataResidenceLabel.trim();
        }

        /**
         * URL publique de l'application ({@code SOCLE_PUBLIC_BASE_URL}), sans « / » final ;
         * lecture seule côté API admin. {@code null} si non configurée.
         */
        public String effectivePublicBaseUrl() {
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
                return null;
            }
            String trimmed = publicBaseUrl.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /** Arborescence dossiers — profondeur max configurable. */
    public record Folders(Integer maxDepth) {
        public int effectiveMaxDepth() {
            return maxDepth == null || maxDepth <= 0 ? 5 : maxDepth;
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
