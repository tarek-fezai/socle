// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.integrations;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** CRUD endpoints webhook — rôle Keycloak {@code integrateur} (SoD ≠ auditeur). */
@RestController
@RequestMapping("/api/v1/webhook-endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService service;

    public WebhookEndpointController(WebhookEndpointService service) {
        this.service = service;
    }

    @GetMapping
    public List<WebhookEndpointService.EndpointView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public WebhookEndpointService.EndpointView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointService.CreateResult create(@RequestBody CreateRequest body) {
        return service.create(body.url(), body.subscribedEvents(), body.status());
    }

    @PutMapping("/{id}")
    public WebhookEndpointService.EndpointView update(
            @PathVariable UUID id,
            @RequestBody UpdateRequest body
    ) {
        return service.update(id, body.url(), body.subscribedEvents(), body.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    public record CreateRequest(String url, List<String> subscribedEvents, String status) {}

    public record UpdateRequest(String url, List<String> subscribedEvents, String status) {}
}
