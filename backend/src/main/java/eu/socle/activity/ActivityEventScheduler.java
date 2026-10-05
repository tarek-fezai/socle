// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.activity;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityEventScheduler {

    private final ActivityEventService activityEventService;

    public ActivityEventScheduler(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }

    /** Tous les jours à 04:30 — purge &gt; 90 jours. */
    @Scheduled(cron = "0 30 4 * * *")
    public void purgeOldEvents() {
        activityEventService.purgeOlderThanRetention();
    }
}
