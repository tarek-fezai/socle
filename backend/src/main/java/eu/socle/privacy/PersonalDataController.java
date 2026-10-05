// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.privacy;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class PersonalDataController {

    private final PersonalDataExportService exportService;

    public PersonalDataController(PersonalDataExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/export")
    public Map<String, Object> export(@AuthenticationPrincipal Jwt jwt) {
        return exportService.exportFor(jwt);
    }
}
