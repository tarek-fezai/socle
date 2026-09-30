package eu.socle.space;

import eu.socle.document.ContentHealthDtos.ContentHealthResponse;
import eu.socle.document.ContentHealthService;
import eu.socle.document.TransclusionGraphDtos.GraphResponse;
import eu.socle.document.TransclusionGraphService;
import eu.socle.space.SpaceDtos.AddOwnerRequest;
import eu.socle.space.SpaceDtos.CreateSpaceRequest;
import eu.socle.space.SpaceDtos.GovernanceView;
import eu.socle.space.SpaceDtos.SpaceView;
import eu.socle.space.SpaceDtos.UpdateSpaceRequest;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {

    private final SpaceService service;
    private final TransclusionGraphService graphService;
    private final ContentHealthService contentHealthService;

    public SpaceController(
            SpaceService service,
            TransclusionGraphService graphService,
            ContentHealthService contentHealthService
    ) {
        this.service = service;
        this.graphService = graphService;
        this.contentHealthService = contentHealthService;
    }

    @GetMapping
    public List<SpaceView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @GetMapping("/{id}")
    public SpaceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    /**
     * Graphe de transclusion centré sur l'espace — voir {@code docs/transclusion-graph.md}.
     */
    @GetMapping("/{id}/graph")
    public GraphResponse graph(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return graphService.graphForSpace(jwt, id);
    }

    /** Documents stale de l'espace — filtrés OpenFGA (voir {@code docs/content-staleness.md}). */
    @GetMapping("/{id}/content-health")
    public ContentHealthResponse contentHealth(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return contentHealthService.forSpace(jwt, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpaceView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSpaceRequest body
    ) {
        return service.create(jwt, body);
    }

    @PutMapping("/{id}")
    public SpaceView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSpaceRequest body
    ) {
        return service.update(jwt, id, body);
    }

    @GetMapping("/{id}/owners")
    public GovernanceView owners(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.governance(jwt, id);
    }

    @PostMapping("/{id}/owners")
    public GovernanceView addOwner(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody AddOwnerRequest body
    ) {
        return service.addOwner(jwt, id, body);
    }

    @DeleteMapping("/{id}/owners/{userId}")
    public GovernanceView removeOwner(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID userId
    ) {
        return service.removeOwner(jwt, id, userId);
    }

    @PutMapping("/{id}/owners/{userId}/responsible")
    public GovernanceView setResponsible(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestBody Map<String, Boolean> body
    ) {
        Boolean responsible = body.get("responsible");
        if (responsible == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "responsible requis (boolean)");
        }
        return service.setResponsible(jwt, id, userId, responsible);
    }
}
