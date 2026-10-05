// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import eu.socle.licence.LicenceDtos.ImportLicenceRequest;
import eu.socle.licence.LicenceDtos.LicenceView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/licence")
public class LicenceAdminController {

    private final LicenceService service;

    public LicenceAdminController(LicenceService service) {
        this.service = service;
    }

    @GetMapping
    public LicenceView get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt);
    }

    @PostMapping("/import")
    public LicenceView importLicence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ImportLicenceRequest body
    ) {
        return service.importLicence(jwt, body);
    }
}
