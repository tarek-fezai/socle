// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.template;

import eu.socle.template.TemplateDtos.CreateTemplateRequest;
import eu.socle.template.TemplateDtos.CreationWarningsResponse;
import eu.socle.template.TemplateDtos.SaveAsTemplateRequest;
import eu.socle.template.TemplateDtos.SaveAsTemplateResponse;
import eu.socle.template.TemplateDtos.TemplateResponse;
import eu.socle.template.TemplateDtos.TemplateSummary;
import eu.socle.template.TemplateDtos.UpdateTemplateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TemplateController {

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<TemplateSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID spaceId
    ) {
        return service.list(jwt, spaceId);
    }

    @GetMapping("/templates/{id}")
    public TemplateResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    /** Avertissements non bloquants (transclusion vers espace {@code restricted}). */
    @GetMapping("/templates/{id}/creation-warnings")
    public CreationWarningsResponse creationWarnings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam UUID spaceId
    ) {
        return service.creationWarnings(jwt, id, spaceId);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTemplateRequest request
    ) {
        return service.create(jwt, request);
    }

    @PatchMapping("/templates/{id}")
    public TemplateResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTemplateRequest request
    ) {
        return service.update(jwt, id, request);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(jwt, id);
    }

    @PostMapping("/documents/{id}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    public SaveAsTemplateResponse saveAsTemplate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody SaveAsTemplateRequest request
    ) {
        return service.saveFromDocument(jwt, id, request);
    }
}
