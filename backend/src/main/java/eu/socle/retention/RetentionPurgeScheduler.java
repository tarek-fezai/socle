// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);

    private final RetentionPurgeService purgeService;

    public RetentionPurgeScheduler(RetentionPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    /** Tous les jours à 03:30 (avant la purge corbeille 04:00) — audit, versions, archives. */
    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        try {
            purgeService.run();
        } catch (RuntimeException e) {
            log.error("Rétention : passage quotidien en échec", e);
        }
    }
}
