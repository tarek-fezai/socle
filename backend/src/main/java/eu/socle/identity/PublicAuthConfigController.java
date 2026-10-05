// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.config.SocleProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration OIDC publique pour le frontend (pas de secret).
 */
@RestController
@RequestMapping("/api/v1/public/auth-config")
public class PublicAuthConfigController {

    private final IdentityProperties properties;
    private final SocleProperties socleProperties;

    public PublicAuthConfigController(IdentityProperties properties, SocleProperties socleProperties) {
        this.properties = properties;
        this.socleProperties = socleProperties;
    }

    @GetMapping
    public Map<String, Object> authConfig() {
        String issuer = properties.getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "issuer-uri non configuré");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authority", issuer);
        body.put("clientId", properties.getClientId());
        body.put("scopes", properties.getScopes());
        body.put("displayName", instanceDisplayName());
        body.put("organizationName", instanceDisplayName());
        // Affichage uniquement — jamais access-policy (groupes / domaines autorisés).
        body.put("passkeyAcrValues", properties.getPasskeyAcrValues());
        body.put("idpDisplayName", properties.getIdpDisplayName());
        body.put("supportContact", properties.getSupportContact());
        if (properties.getAuthorizationEndpoint() != null) {
            body.put("authorizationEndpoint", properties.getAuthorizationEndpoint());
        }
        if (properties.getTokenEndpoint() != null) {
            body.put("tokenEndpoint", properties.getTokenEndpoint());
        }
        if (properties.getEndSessionEndpoint() != null) {
            body.put("endSessionEndpoint", properties.getEndSessionEndpoint());
        }
        if (properties.getJwksUri() != null) {
            body.put("jwksUri", properties.getJwksUri());
        }
        return body;
    }

    private String instanceDisplayName() {
        SocleProperties.Instance instance = socleProperties.instance();
        return instance == null ? "Socle" : instance.effectiveDisplayName();
    }
}
