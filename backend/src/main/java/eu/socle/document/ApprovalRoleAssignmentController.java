// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.document.ApprovalRoleAssignmentService.AssignRequest;
import eu.socle.document.ApprovalRoleAssignmentService.AssignmentView;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-role-assignments")
public class ApprovalRoleAssignmentController {

    private final ApprovalRoleAssignmentService service;
    private final UserSyncService userSyncService;

    public ApprovalRoleAssignmentController(
            ApprovalRoleAssignmentService service,
            UserSyncService userSyncService
    ) {
        this.service = service;
        this.userSyncService = userSyncService;
    }

    @GetMapping
    public List<AssignmentView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID roleId,
            @RequestParam(required = false) UUID spaceId
    ) {
        UserEntity actor = requireActor(jwt);
        return service.list(actor.getId(), roleId, spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView assign(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Body body
    ) {
        UserEntity actor = requireActor(jwt);
        return service.assign(actor.getId(), new AssignRequest(
                body.roleId(),
                body.subjectType(),
                body.subjectId(),
                body.scopeType(),
                body.scopeRef()
        ));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        UserEntity actor = requireActor(jwt);
        service.unassign(actor.getId(), id);
    }

    private UserEntity requireActor(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        return userSyncService.syncFromJwt(jwt);
    }

    public record Body(
            @NotNull UUID roleId,
            @NotBlank String subjectType,
            @NotNull UUID subjectId,
            @NotBlank String scopeType,
            String scopeRef
    ) {}
}
