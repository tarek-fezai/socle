// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.document.DocumentVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Migration FGA visibility-v1 :
 * <ul>
 *   <li>owner direct du <strong>créateur</strong> ({@code documents.created_by}) → editor + direct_access</li>
 *   <li>parent → parent + inherit_from (si visibility ≠ restricted)</li>
 *   <li>grants directs existants → tuple {@code direct_access}</li>
 * </ul>
 * Les owners délégués via Access ne sont <strong>pas</strong> convertis.
 * Exécution unique journalisée dans {@code authz_migrations}.
 */
@Service
public class VisibilityTupleMigrationService {

    public static final String MIGRATION_NAME = "visibility-v1";

    private static final Logger log = LoggerFactory.getLogger(VisibilityTupleMigrationService.class);

    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VisibilityTupleMigrationService(
            AuthorizationService authorizationService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean isApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                MIGRATION_NAME);
        return n != null && n > 0;
    }

    /**
     * @param force si false et déjà appliquée → no-op ; si true → rejoue les ops idempotentes
     *              sans retoucher les owners non-créateurs, puis met à jour le rapport.
     */
    public MigrationReport migrate(boolean force) {
        if (!force && isApplied()) {
            log.info("FGA migration {} déjà appliquée — skip", MIGRATION_NAME);
            return MigrationReport.skippedReport();
        }
        MigrationReport report = runMigrationBody();
        persistApplied(report);
        return report;
    }

    /** Endpoint admin / démarrage : force=false (une seule fois). */
    public MigrationReport migrate() {
        return migrate(false);
    }

    /** Relance manuelle idempotente (admin). */
    public MigrationReport migrateForce() {
        return migrate(true);
    }

    private MigrationReport runMigrationBody() {
        int ownersConverted = 0;
        int inheritAdded = 0;
        int directAccessAdded = 0;
        int docsScanned = 0;
        int errors = 0;
        int delegatedOwnersPreserved = 0;

        List<DocRow> docs = jdbc.query(
                "SELECT id, visibility, created_by FROM documents WHERE deleted_at IS NULL",
                (rs, i) -> new DocRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("visibility"),
                        (UUID) rs.getObject("created_by")));

        for (DocRow doc : docs) {
            docsScanned++;
            try {
                List<AuthorizationService.AccessTuple> tuples =
                        authorizationService.readAllTuples("document", doc.id());

                String parentObject = null;
                for (AuthorizationService.AccessTuple t : tuples) {
                    if ("parent".equals(t.relation())) {
                        parentObject = t.user();
                    }
                }

                String creatorFga = doc.createdBy() != null ? "user:" + doc.createdBy() : null;

                for (AuthorizationService.AccessTuple t : tuples) {
                    if (!"owner".equals(t.relation())
                            || t.user() == null
                            || !t.user().startsWith("user:")
                            || AuthorizationService.WILDCARD_USER.equals(t.user())) {
                        continue;
                    }
                    if (creatorFga != null && creatorFga.equals(t.user())) {
                        authorizationService.convertDirectOwnerToEditor(doc.id(), t.user());
                        ownersConverted++;
                        log.info("FGA migration: créateur {} owner → editor on document:{}", t.user(), doc.id());
                    } else {
                        delegatedOwnersPreserved++;
                        log.debug(
                                "FGA migration: owner délégué {} préservé sur document:{}",
                                t.user(), doc.id());
                    }
                }

                boolean needsInherit = DocumentVisibility.inheritsFromParent(
                        doc.visibility() == null ? DocumentVisibility.SPACE : doc.visibility());
                boolean hasInherit = tuples.stream().anyMatch(t -> "inherit_from".equals(t.relation()));
                if (needsInherit && !hasInherit && parentObject != null) {
                    authorizationService.ensureInheritFrom(doc.id(), parentObject);
                    inheritAdded++;
                }

                // Backfill direct_access pour tout grant user/group owner|editor|viewer
                List<AuthorizationService.AccessTuple> after =
                        authorizationService.readAllTuples("document", doc.id());
                for (AuthorizationService.AccessTuple t : after) {
                    if (!SetOf("owner", "editor", "viewer").contains(t.relation())) {
                        continue;
                    }
                    if (t.user() == null
                            || AuthorizationService.WILDCARD_USER.equals(t.user())
                            || t.user().startsWith("space:")
                            || t.user().startsWith("folder:")) {
                        continue;
                    }
                    boolean hasDirect = after.stream().anyMatch(x ->
                            AuthorizationService.RELATION_DIRECT_ACCESS.equals(x.relation())
                                    && t.user().equals(x.user()));
                    if (!hasDirect) {
                        authorizationService.ensureDirectAccess(doc.id(), t.user());
                        directAccessAdded++;
                    }
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("FGA visibility migration failed for document:{}", doc.id(), e);
            }
        }

        MigrationReport report = new MigrationReport(
                false, docsScanned, ownersConverted, inheritAdded, directAccessAdded,
                delegatedOwnersPreserved, errors);
        log.info(
                "FGA visibility migration done: scanned={}, creators→editor={}, inherit_from={}, "
                        + "direct_access={}, delegatedOwnersPreserved={}, errors={}",
                report.documentsScanned(),
                report.ownersConvertedToEditor(),
                report.inheritFromAdded(),
                report.directAccessAdded(),
                report.delegatedOwnersPreserved(),
                report.errors());
        return report;
    }

    private void persistApplied(MigrationReport report) {
        String json;
        try {
            json = objectMapper.writeValueAsString(report.toMap());
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update("""
                INSERT INTO authz_migrations (name, applied_at, report)
                VALUES (?, now(), ?::jsonb)
                ON CONFLICT (name) DO UPDATE
                  SET applied_at = now(), report = EXCLUDED.report
                """, MIGRATION_NAME, json);
    }

    private static java.util.Set<String> SetOf(String... values) {
        return java.util.Set.of(values);
    }

    public record MigrationReport(
            boolean skipped,
            int documentsScanned,
            int ownersConvertedToEditor,
            int inheritFromAdded,
            int directAccessAdded,
            int delegatedOwnersPreserved,
            int errors
    ) {
        static MigrationReport skippedReport() {
            return new MigrationReport(true, 0, 0, 0, 0, 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skipped", skipped);
            m.put("migration", MIGRATION_NAME);
            m.put("documentsScanned", documentsScanned);
            m.put("ownersConvertedToEditor", ownersConvertedToEditor);
            m.put("inheritFromAdded", inheritFromAdded);
            m.put("directAccessAdded", directAccessAdded);
            m.put("delegatedOwnersPreserved", delegatedOwnersPreserved);
            m.put("errors", errors);
            return m;
        }
    }

    private record DocRow(UUID id, String visibility, UUID createdBy) {}
}

@Component
@ConditionalOnProperty(
        name = "socle.openfga.visibility-migration-on-startup",
        havingValue = "true",
        matchIfMissing = false
)
class VisibilityTupleMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VisibilityTupleMigrationRunner.class);

    private final VisibilityTupleMigrationService migrationService;

    VisibilityTupleMigrationRunner(VisibilityTupleMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrationService.migrate();
        } catch (RuntimeException e) {
            log.error(
                    "FGA visibility migration au démarrage a échoué "
                            + "(réessayer via POST /api/v1/admin/authz/migrate-visibility)",
                    e);
        }
    }
}
