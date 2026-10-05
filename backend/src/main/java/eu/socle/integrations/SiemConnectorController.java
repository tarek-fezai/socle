// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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
import java.util.Map;
import java.util.UUID;

/**
 * CRUD connecteurs SIEM — rôle Keycloak {@code integrateur}
 * (séparé de {@code auditeur} : SoD configuration ≠ supervision).
 */
@RestController
@RequestMapping("/api/v1/siem-connectors")
public class SiemConnectorController {

    private final SiemConnectorService service;

    public SiemConnectorController(SiemConnectorService service) {
        this.service = service;
    }

    @GetMapping
    public List<SiemConnectorService.ConnectorView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SiemConnectorService.ConnectorView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SiemConnectorService.ConnectorView create(@RequestBody CreateRequest body) {
        return service.create(body.provider(), body.config());
    }

    @PutMapping("/{id}")
    public SiemConnectorService.ConnectorView update(
            @PathVariable UUID id,
            @RequestBody UpdateRequest body
    ) {
        return service.update(id, body.config(), body.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    public SiemConnectorService.TestResult test(@PathVariable UUID id) {
        return service.test(id);
    }

    public record CreateRequest(String provider, Map<String, Object> config) {}

    public record UpdateRequest(Map<String, Object> config, String status) {}
}
