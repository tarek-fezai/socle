// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentViewCountScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentViewCountScheduler.class);

    private final DocumentViewService documentViewService;

    public DocumentViewCountScheduler(DocumentViewService documentViewService) {
        this.documentViewService = documentViewService;
    }

    /** Tous les jours à 04:45 — purge agrégats &gt; 13 mois. */
    @Scheduled(cron = "0 45 4 * * *")
    public void purgeOldCounts() {
        int n = documentViewService.purgeOlderThanRetention();
        if (n > 0) {
            log.info("document_view_counts : {} ligne(s) purgée(s) (> {} mois)",
                    n, DocumentViewService.RETENTION_MONTHS);
        }
    }
}
