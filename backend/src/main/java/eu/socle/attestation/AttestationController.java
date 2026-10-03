// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attestation;

import eu.socle.attestation.AttestationDtos.AcknowledgmentView;
import eu.socle.attestation.AttestationDtos.ActiveAttestation;
import eu.socle.attestation.AttestationDtos.CampaignView;
import eu.socle.attestation.AttestationDtos.CreateCampaignRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/attestations")
public class AttestationController {

    private final AttestationService service;

    public AttestationController(AttestationService service) {
        this.service = service;
    }

    /** Création d'une campagne — owners de l'espace uniquement. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody CreateCampaignRequest request
    ) {
        return service.create(jwt, id, request);
    }

    /** Clôture d'une campagne — owners de l'espace uniquement. */
    @DeleteMapping("/{campaignId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID campaignId
    ) {
        service.close(jwt, id, campaignId);
    }

    /** Campagne ouverte concernant l'utilisateur courant ; 204 si aucune. */
    @GetMapping("/active")
    public ResponseEntity<ActiveAttestation> active(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return service.active(jwt, id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** « J'ai lu et compris » — enregistre l'accusé pour la version courante du document. */
    @PostMapping("/{campaignId}/acknowledge")
    public ActiveAttestation acknowledge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID campaignId
    ) {
        return service.acknowledge(jwt, id, campaignId);
    }

    /** Liste nominative des accusés — owners de l'espace uniquement. */
    @GetMapping("/{campaignId}/acknowledgments")
    public List<AcknowledgmentView> acknowledgments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID campaignId
    ) {
        return service.acknowledgments(jwt, id, campaignId);
    }
}
