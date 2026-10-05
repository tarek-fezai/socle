// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Reprise des entrées {@code pending}/{@code running} au démarrage. */
@Component
public class GitPurgeQueueStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GitPurgeQueueStartupRunner.class);

    private final GitPurgeQueueService queueService;

    public GitPurgeQueueStartupRunner(GitPurgeQueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int recovered = queueService.recoverInterrupted();
        if (recovered > 0) {
            log.info("File purge Git : {} entrée(s) running remise(s) en pending", recovered);
        }
        queueService.processDueEntries(50);
    }
}
