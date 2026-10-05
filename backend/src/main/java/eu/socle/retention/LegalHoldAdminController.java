// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.retention.LegalHoldDtos.LegalHoldListResponse;
import eu.socle.retention.LegalHoldDtos.LegalHoldView;
import eu.socle.retention.LegalHoldDtos.PlaceLegalHoldRequest;
import eu.socle.retention.LegalHoldDtos.ReleaseLegalHoldRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/legal-holds")
public class LegalHoldAdminController {

    private final LegalHoldService service;

    public LegalHoldAdminController(LegalHoldService service) {
        this.service = service;
    }

    @GetMapping
    public LegalHoldListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly
    ) {
        return service.list(jwt, activeOnly);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LegalHoldView place(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PlaceLegalHoldRequest request
    ) {
        return service.place(jwt, request);
    }

    @PostMapping("/{id}/release")
    public LegalHoldView release(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody ReleaseLegalHoldRequest request
    ) {
        return service.release(jwt, id, request);
    }
}
