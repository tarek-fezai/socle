// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.notification;

import eu.socle.notification.NotificationService.NotificationPage;
import eu.socle.notification.NotificationService.NotificationView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return notificationService.list(jwt, unreadOnly, offset, limit);
    }

    @PostMapping("/{id}/read")
    public NotificationView markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return notificationService.markRead(jwt, id);
    }
}
