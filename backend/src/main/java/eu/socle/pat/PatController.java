// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.identity.IdentityFacade;
import eu.socle.user.MeController;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Jetons d'accès personnels de l'utilisateur courant (refusés à l'authentification par PAT). */
@RestController
@RequestMapping("/api/v1/me/tokens")
public class PatController {

    private final PatService patService;
    private final IdentityFacade identityFacade;

    public PatController(PatService patService, IdentityFacade identityFacade) {
        this.patService = patService;
        this.identityFacade = identityFacade;
    }

    @GetMapping
    @Operation(operationId = "listPersonalAccessTokens")
    public List<PatDtos.TokenView> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = MeController.requireUserId(requireJwt(jwt), identityFacade);
        return patService.list(userId).stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createPersonalAccessToken")
    public PatDtos.Created create(@AuthenticationPrincipal Jwt jwt, @RequestBody PatDtos.CreateRequest request) {
        UUID userId = MeController.requireUserId(requireJwt(jwt), identityFacade);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corps requis");
        }
        PatService.Created created = patService.create(
                userId, request.name(), request.scope(), request.expiresInDays());
        return new PatDtos.Created(view(created.row()), created.token());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokePersonalAccessToken")
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID userId = MeController.requireUserId(requireJwt(jwt), identityFacade);
        if (!patService.revoke(userId, id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Jeton introuvable");
        }
    }

    private PatDtos.TokenView view(PatRepository.PatRow row) {
        return new PatDtos.TokenView(row.id(), row.name(), row.last4(), row.scope(),
                row.createdAt(), row.expiresAt(), row.lastUsedAt(), patService.status(row));
    }

    private static Jwt requireJwt(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        return jwt;
    }
}
