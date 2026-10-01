// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.webhook;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks/deliveries")
public class WebhookDeliveryController {

    private final WebhookDeliveryQueryService queryService;

    public WebhookDeliveryController(WebhookDeliveryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public WebhookDeliveryQueryService.DeliveryPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return queryService.list(endpointId, status, offset, limit);
    }
}
