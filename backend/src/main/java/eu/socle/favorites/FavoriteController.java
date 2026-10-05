// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.favorites;

import eu.socle.favorites.FavoriteDtos.FavoriteItem;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {

    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @GetMapping
    public List<FavoriteItem> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }

    @PutMapping("/{type}/{id}")
    public FavoriteItem put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String type,
            @PathVariable UUID id
    ) {
        return service.put(jwt, parseType(type), id);
    }

    @DeleteMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String type,
            @PathVariable UUID id
    ) {
        service.delete(jwt, parseType(type), id);
    }

    private static FavoriteTargetType parseType(String type) {
        try {
            return FavoriteTargetType.fromPath(type);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
