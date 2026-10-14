// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InvitationExpiryJob {

    private final InvitationService service;

    public InvitationExpiryJob(InvitationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 300_000L)
    public void expirePending() {
        service.expireDue();
    }
}
