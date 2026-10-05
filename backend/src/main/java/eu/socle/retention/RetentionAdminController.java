// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.retention.RetentionDtos.RetentionSettingsView;
import eu.socle.retention.RetentionDtos.UpdateRetentionRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retention")
public class RetentionAdminController {

    private final RetentionSettingsService service;

    public RetentionAdminController(RetentionSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public RetentionSettingsView get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt);
    }

    @PutMapping
    public RetentionSettingsView update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateRetentionRequest request
    ) {
        return service.update(jwt, request);
    }
}
