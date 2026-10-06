// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.customfield;

import eu.socle.customfield.CustomFieldAdminDtos.CreateFieldRequest;
import eu.socle.customfield.CustomFieldAdminDtos.FieldAdminView;
import eu.socle.customfield.CustomFieldAdminDtos.FieldListResponse;
import eu.socle.customfield.CustomFieldAdminDtos.UpdateFieldRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/custom-fields")
public class CustomFieldAdminController {

    private final CustomFieldAdminService service;

    public CustomFieldAdminController(CustomFieldAdminService service) {
        this.service = service;
    }

    @GetMapping
    public FieldListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FieldAdminView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFieldRequest request
    ) {
        return service.create(jwt, request);
    }

    @PutMapping("/{id}")
    public FieldAdminView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFieldRequest request
    ) {
        return service.update(jwt, id, request);
    }

    @DeleteMapping("/{id}")
    public FieldAdminView delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return service.deleteOrArchive(jwt, id);
    }
}
