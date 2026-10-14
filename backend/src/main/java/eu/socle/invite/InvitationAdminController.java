// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/invitations")
public class InvitationAdminController {

    private final InvitationService service;

    public InvitationAdminController(InvitationService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvitationDtos.InvitationView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDtos.InvitationView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InvitationDtos.CreateRequest body
    ) {
        return service.create(jwt, body);
    }

    @PostMapping("/{id}/resend")
    public InvitationDtos.InvitationView resend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return service.resend(jwt, id);
    }

    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.revoke(jwt, id);
    }
}
