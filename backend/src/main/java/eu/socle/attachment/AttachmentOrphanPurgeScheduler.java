// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AttachmentOrphanPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentOrphanPurgeScheduler.class);

    private final AttachmentService attachmentService;

    public AttachmentOrphanPurgeScheduler(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /** Tous les jours à 04:20 — purge des pièces jointes jamais référencées. */
    @Scheduled(cron = "0 20 4 * * *")
    public void purgeOrphans() {
        int n = attachmentService.purgeOrphans();
        if (n > 0) {
            log.info("Pièces jointes orphelines : {} fichier(s) purgé(s)", n);
        }
    }
}
