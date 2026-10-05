// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.tag.TagAdminDtos.CreateTagRequest;
import eu.socle.tag.TagAdminDtos.MergeTagRequest;
import eu.socle.tag.TagAdminDtos.RenameTagRequest;
import eu.socle.tag.TagAdminDtos.TagAdminSummary;
import eu.socle.tag.TagAdminDtos.TagAdminView;
import eu.socle.tag.TagAdminDtos.TagCreationPolicyRequest;
import eu.socle.tag.TagAdminDtos.TagListResponse;
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
@RequestMapping("/api/v1/admin/tags")
public class TagAdminController {

    private final TagAdminService service;

    public TagAdminController(TagAdminService service) {
        this.service = service;
    }

    @GetMapping
    public TagListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @GetMapping("/summary")
    public TagAdminSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return service.summary(jwt);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagAdminView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTagRequest request
    ) {
        return service.create(jwt, request);
    }

    @PutMapping("/{id}/name")
    public TagAdminView rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody RenameTagRequest request
    ) {
        return service.rename(jwt, id, request);
    }

    @PostMapping("/{id}/merge")
    public TagAdminView merge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody MergeTagRequest request
    ) {
        return service.merge(jwt, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(jwt, id);
    }

    @PutMapping("/creation-policy")
    public TagAdminSummary updateCreationPolicy(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TagCreationPolicyRequest request
    ) {
        return service.updateCreationPolicy(jwt, request);
    }
}
