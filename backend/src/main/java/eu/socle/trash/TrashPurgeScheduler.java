// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.trash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final TrashService trashService;

    public TrashPurgeScheduler(TrashService trashService) {
        this.trashService = trashService;
    }

    /** Tous les jours à 04:00 — purge réelle des items dont purge_at est dépassé. */
    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpired() {
        int n = trashService.purgeExpired();
        if (n > 0) {
            log.info("Corbeille : {} ressource(s) purgée(s) définitivement", n);
        }
    }
}
