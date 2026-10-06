// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GitPurgeQueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitPurgeQueueScheduler.class);

    private final GitPurgeQueueService queueService;

    public GitPurgeQueueScheduler(GitPurgeQueueService queueService) {
        this.queueService = queueService;
    }

    /** Retentatives avec backoff pour les purges Git en échec ou en attente. */
    @Scheduled(fixedDelayString = "${socle.git-purge-queue.poll-ms:60000}")
    public void poll() {
        int n = queueService.processDueEntries(10);
        if (n > 0) {
            log.info("File purge Git : {} entrée(s) traitée(s)", n);
        }
    }
}
