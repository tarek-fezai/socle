// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Notification in-app 7 jours avant l'expiration d'un jeton d'accès personnel. */
@Component
public class PatExpiryNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(PatExpiryNotificationJob.class);

    private final PatService patService;

    public PatExpiryNotificationJob(PatService patService) {
        this.patService = patService;
    }

    @Scheduled(
            initialDelayString = "${socle.pat.expiry-notification-initial-delay:PT1M}",
            fixedDelayString = "${socle.pat.expiry-notification-interval:PT1H}")
    public void run() {
        try {
            int created = patService.notifyExpiringTokens();
            if (created > 0) {
                log.info("Jetons d'accès personnels : {} notification(s) d'expiration J-7", created);
            }
        } catch (RuntimeException e) {
            log.warn("Notifications d'expiration PAT : échec ({})", e.getMessage());
        }
    }
}
