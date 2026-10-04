// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.poll;

import eu.socle.poll.PollDtos.PollView;
import eu.socle.poll.PollDtos.VoteRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/polls/{id}")
public class PollController {

    private final PollService service;

    public PollController(PollService service) {
        this.service = service;
    }

    /** Totaux agrégés + vote propre ; jamais la liste des votants. */
    @GetMapping
    public PollView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    /** Vote (ou modification) — viewer ; autorisé pendant une approbation. */
    @PutMapping("/vote")
    public PollView vote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody VoteRequest request
    ) {
        return service.vote(jwt, id, request.option());
    }

    /** Fermeture — editor uniquement. */
    @PostMapping("/close")
    public PollView close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.close(jwt, id);
    }
}
