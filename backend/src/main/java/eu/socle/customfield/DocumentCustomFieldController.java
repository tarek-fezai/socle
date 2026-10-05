// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.customfield;

import com.fasterxml.jackson.databind.JsonNode;
import eu.socle.customfield.DocumentCustomFieldService.CustomFieldView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/custom-fields")
public class DocumentCustomFieldController {

    /** @param value JSON typé selon le champ ; {@code null} ou vide = effacer */
    public record SetValueRequest(JsonNode value) {}

    private final DocumentCustomFieldService service;

    public DocumentCustomFieldController(DocumentCustomFieldService service) {
        this.service = service;
    }

    /** Définitions applicables (actives, portée toutes espaces ou espace du document) + valeurs. */
    @GetMapping
    public List<CustomFieldView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.list(jwt, id);
    }

    @PutMapping("/{fieldId}")
    public CustomFieldView set(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID fieldId,
            @RequestBody SetValueRequest request
    ) {
        return service.setValue(jwt, id, fieldId, request.value());
    }
}
