// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import eu.socle.document.DocumentLinkService;
import eu.socle.document.VersionAuthorshipBackfillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin authz — réservé {@code administrateur-systeme} (SecurityConfig {@code /api/v1/admin/**}).
 */
@RestController
@RequestMapping("/api/v1/admin/authz")
public class VisibilityMigrationController {

    private final VisibilityTupleMigrationService migrationService;
    private final VisibilityDriftService driftService;
    private final DocumentLinkService documentLinkService;
    private final VersionAuthorshipBackfillService versionAuthorshipBackfillService;

    public VisibilityMigrationController(
            VisibilityTupleMigrationService migrationService,
            VisibilityDriftService driftService,
            DocumentLinkService documentLinkService,
            VersionAuthorshipBackfillService versionAuthorshipBackfillService
    ) {
        this.migrationService = migrationService;
        this.driftService = driftService;
        this.documentLinkService = documentLinkService;
        this.versionAuthorshipBackfillService = versionAuthorshipBackfillService;
    }

    /**
     * Migration visibility-v1. Par défaut no-op si déjà appliquée.
     * {@code ?force=true} rejoue les ops idempotentes (ne convertit jamais un owner non-créateur).
     */
    @PostMapping("/migrate-visibility")
    public Map<String, Object> migrateVisibility(
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return (force ? migrationService.migrateForce() : migrationService.migrate()).toMap();
    }

    /** Lecture seule — divergences colonne visibility ↔ tuples user:* / inherit_from. */
    @GetMapping("/visibility-drift")
    public Map<String, Object> visibilityDrift() {
        return driftService.listDrift().toMap();
    }

    /** Lecture seule — {@code direct_access} sans owner/editor/viewer direct. */
    @GetMapping("/direct-access-drift")
    public Map<String, Object> directAccessDrift() {
        return driftService.listDirectAccessDrift().toMap();
    }

    /** Supprime les {@code direct_access} orphelins (best-effort). */
    @PostMapping("/direct-access-drift/repair")
    public Map<String, Object> repairDirectAccessDrift() {
        return driftService.repairOrphanDirectAccess().toMap();
    }

    /** Backfill {@code document_links} depuis le contenu canonique (idempotent). */
    @PostMapping("/backfill-document-links")
    public Map<String, Object> backfillDocumentLinks(
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return documentLinkService.backfill(force).toMap();
    }

    /** Divergence {@code document_links} ↔ contenu canonique. */
    @GetMapping("/document-links-drift")
    public Map<String, Object> documentLinksDrift() {
        return documentLinkService.listDrift().toMap();
    }

    /**
     * Backfill {@code document_versions.author_id}/{@code archived_by} + {@code documents.updated_by}
     * (idempotent, journal {@code version-authorship-v1}).
     */
    @PostMapping("/backfill-version-authorship")
    public Map<String, Object> backfillVersionAuthorship(
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return versionAuthorshipBackfillService.backfill(force).toMap();
    }
}
