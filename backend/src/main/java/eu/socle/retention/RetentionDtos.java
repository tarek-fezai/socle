// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.retention;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public final class RetentionDtos {

    private RetentionDtos() {}

    /** Politique de rétention courante (lecture interne, sans métadonnées d'affichage). */
    public record RetentionPolicy(
            int auditRetentionMonths,
            String versionRetentionMode,
            Integer versionRetentionValue,
            int archivedDocsRetentionYears,
            LocalDate processingRegisterReviewedAt
    ) {}

    /** Réponse GET/PUT {@code /api/v1/admin/retention}. */
    public record RetentionSettingsView(
            int auditRetentionMonths,
            /** {@code unlimited} | {@code months} | {@code count}. */
            String versionRetentionMode,
            Integer versionRetentionValue,
            int archivedDocsRetentionYears,
            LocalDate processingRegisterReviewedAt,
            /** Libellé {@code SOCLE_DATA_RESIDENCE_LABEL} — {@code null} si non configuré (lecture seule). */
            String dataResidenceLabel,
            long activeLegalHolds,
            Instant lastPurgeRanAt,
            Instant updatedAt,
            long gitPurgePendingCount,
            long gitPurgeFailedCount,
            @Schema(types = { "string", "null" }, nullable = true) String gitPurgeLastError
    ) {}

    /** PUT = remplacement complet ; {@code processingRegisterReviewedAt = null} efface la date. */
    public record UpdateRetentionRequest(
            Integer auditRetentionMonths,
            String versionRetentionMode,
            Integer versionRetentionValue,
            Integer archivedDocsRetentionYears,
            LocalDate processingRegisterReviewedAt
    ) {}

    /** Compteurs d'un passage de {@code RetentionPurgeService} (audités tels quels). */
    public record RetentionPurgeResult(
            long auditEventsDeleted,
            long versionsDeleted,
            long archivedDocumentsPurged,
            long archivedSpacesPurged,
            long skippedLegalHold,
            long failures
    ) {
        public Map<String, Object> asMetadata() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("auditEventsDeleted", auditEventsDeleted);
            m.put("versionsDeleted", versionsDeleted);
            m.put("archivedDocumentsPurged", archivedDocumentsPurged);
            m.put("archivedSpacesPurged", archivedSpacesPurged);
            m.put("skippedLegalHold", skippedLegalHold);
            m.put("failures", failures);
            return m;
        }
    }
}
