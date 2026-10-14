// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationAcceptController {

    private final InvitationService service;

    public InvitationAcceptController(InvitationService service) {
        this.service = service;
    }

    @PostMapping("/accept")
    public InvitationDtos.AcceptResponse accept(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InvitationDtos.AcceptRequest body
    ) {
        return service.accept(jwt, body.token());
    }
}
