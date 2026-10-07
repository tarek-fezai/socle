// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.admin;

import eu.socle.admin.AdminOverviewDtos.AdminOverviewView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/overview")
public class AdminOverviewController {

    private final AdminOverviewService service;

    public AdminOverviewController(AdminOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public AdminOverviewView overview(@AuthenticationPrincipal Jwt jwt) {
        return service.overview(jwt);
    }
}
