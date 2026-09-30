package eu.socle.team;

import eu.socle.team.GroupDtos.AddMemberRequest;
import eu.socle.team.GroupDtos.CreateGroupRequest;
import eu.socle.team.GroupDtos.GroupView;
import eu.socle.team.GroupDtos.UpdateGroupRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService service;

    public GroupController(GroupService service) {
        this.service = service;
    }

    @GetMapping
    public List<GroupView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @GetMapping("/{id}")
    public GroupView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateGroupRequest body
    ) {
        return service.create(jwt, body);
    }

    @PutMapping("/{id}")
    public GroupView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateGroupRequest body
    ) {
        return service.update(jwt, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(jwt, id);
    }

    @PostMapping("/{id}/members")
    public GroupView addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest body
    ) {
        return service.addMember(jwt, id, body);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public GroupView removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID userId
    ) {
        return service.removeMember(jwt, id, userId);
    }
}
