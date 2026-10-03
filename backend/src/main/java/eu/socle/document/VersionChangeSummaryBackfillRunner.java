// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Backfill du décalage {@code change_summary} — relational uniquement.
 *
 * <p>Justification : {@code socle.storage.provider} est global à l'instance et n'est
 * pas consultable en SQL Flyway. Un décalage aveugle en migration casserait le mode
 * git (messages de commit HEAD déjà corrects ; métadonnées d'archive éventuellement
 * déjà alignées via writeCurrentContent). En relational, les résumés n'existent que
 * dans Postgres → correction idempotente au démarrage.
 */
@Component
@Order(200)
@ConditionalOnProperty(name = "socle.storage.provider", havingValue = "relational")
public class VersionChangeSummaryBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VersionChangeSummaryBackfillRunner.class);

    private final VersionChangeSummaryBackfillService backfillService;

    public VersionChangeSummaryBackfillRunner(VersionChangeSummaryBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            VersionChangeSummaryBackfillService.BackfillReport report = backfillService.backfill(false);
            if (report.skipped()) {
                log.debug("Backfill {} déjà appliqué", VersionChangeSummaryBackfillService.BACKFILL_NAME);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Backfill " + VersionChangeSummaryBackfillService.BACKFILL_NAME + " a échoué", e);
        }
    }
}
