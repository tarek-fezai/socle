// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration identité découplée de l'IdP (Keycloak par défaut).
 *
 * <p>Préfixe {@code socle.identity}. Les défauts reproduisent le comportement
 * Keycloak historique ({@code realm_access.roles}, mapping 1:1 des noms de rôles).
 */
@ConfigurationProperties(prefix = "socle.identity")
public class IdentityProperties {

    public enum RoleSource {
        CLAIMS,
        INTERNAL,
        BOTH
    }

    /** Issuer OIDC (également exposé au frontend via auth-config). */
    private String issuerUri = "http://localhost:8081/realms/socle";

    private String subjectClaim = "sub";
    private String emailClaim = "email";
    private String nameClaim = "name";
    /** Chemin pointé dans le JWT (ex. {@code realm_access.roles}, {@code roles}, {@code groups}). */
    private String rolesClaim = "realm_access.roles";

    private Map<String, SocleRole> roleMapping = defaultRoleMapping();

    private SocleRole defaultRole = SocleRole.CONTRIBUTEUR;
    private RoleSource roleSource = RoleSource.CLAIMS;

    /** Subjects OIDC bootstrapés admin à la première connexion (modes INTERNAL / BOTH). */
    private List<String> bootstrapAdminSubjects = new ArrayList<>();

    /** Client OIDC public (SPA) — exposé via {@code /api/v1/public/auth-config}. */
    private String clientId = "socle-frontend";
    private List<String> scopes = new ArrayList<>(List.of("openid", "profile", "email"));
    /** Override optionnel de l'endpoint d'autorisation (sinon dérivé de l'issuer). */
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String endSessionEndpoint;
    private String jwksUri;

    /**
     * Si {@code true} : rattachement automatique à la 1ʳᵉ connexion lorsque
     * {@code email_verified=true}, un seul compte porte l'e-mail, et aucune
     * identité pour cet issuer. <strong>Risque de prise de contrôle de compte</strong>
     * si l'IdP ment sur la vérification — défaut {@code false}.
     */
    private boolean linkByVerifiedEmail = false;

    /** Politique d'accès (qui peut se connecter) — jamais exposée au frontend. */
    private AccessPolicy accessPolicy = new AccessPolicy();

    /** Valeurs {@code acr_values} proposées pour un parcours passkey (exposé via auth-config). */
    private String passkeyAcrValues = "";
    /** Nom affiché de l'IdP sur l'écran de connexion (exposé via auth-config). */
    private String idpDisplayName = "";
    /** Contact support affiché lors d'un refus d'accès (exposé via auth-config). */
    private String supportContact = "";

    public enum AccessMode {
        /** Création de compte à la première connexion si la politique le permet. */
        JIT,
        /** Comme JIT, mais l'utilisateur doit appartenir à un groupe autorisé. */
        REQUIRE_GROUP,
        /** Seuls les comptes déjà rattachés à une identité (issuer, sub) peuvent se connecter. */
        PROVISIONED_ONLY
    }

    /**
     * Politique d'accès : {@code socle.identity.access-policy}.
     * Ne jamais renvoyer {@code allowedGroups} / {@code allowedEmailDomains} à un client.
     */
    public static class AccessPolicy {

        private AccessMode mode = AccessMode.JIT;
        private List<String> allowedGroups = new ArrayList<>();
        private String groupsClaim = "groups";
        /** Vide = tous les domaines. */
        private List<String> allowedEmailDomains = new ArrayList<>();

        public AccessMode getMode() {
            return mode;
        }

        public void setMode(AccessMode mode) {
            this.mode = mode != null ? mode : AccessMode.JIT;
        }

        public List<String> getAllowedGroups() {
            return allowedGroups;
        }

        public void setAllowedGroups(List<String> allowedGroups) {
            this.allowedGroups = allowedGroups != null ? allowedGroups : new ArrayList<>();
        }

        public String getGroupsClaim() {
            return groupsClaim;
        }

        public void setGroupsClaim(String groupsClaim) {
            this.groupsClaim = groupsClaim != null && !groupsClaim.isBlank() ? groupsClaim : "groups";
        }

        public List<String> getAllowedEmailDomains() {
            return allowedEmailDomains;
        }

        public void setAllowedEmailDomains(List<String> allowedEmailDomains) {
            this.allowedEmailDomains = allowedEmailDomains != null ? allowedEmailDomains : new ArrayList<>();
        }
    }

    private static Map<String, SocleRole> defaultRoleMapping() {
        Map<String, SocleRole> m = new LinkedHashMap<>();
        m.put("contributeur", SocleRole.CONTRIBUTEUR);
        m.put("auditeur", SocleRole.AUDITEUR);
        m.put("integrateur", SocleRole.INTEGRATEUR);
        m.put("administrateur-systeme", SocleRole.ADMINISTRATEUR_SYSTEME);
        return m;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getSubjectClaim() {
        return subjectClaim;
    }

    public void setSubjectClaim(String subjectClaim) {
        this.subjectClaim = subjectClaim;
    }

    public String getEmailClaim() {
        return emailClaim;
    }

    public void setEmailClaim(String emailClaim) {
        this.emailClaim = emailClaim;
    }

    public String getNameClaim() {
        return nameClaim;
    }

    public void setNameClaim(String nameClaim) {
        this.nameClaim = nameClaim;
    }

    public String getRolesClaim() {
        return rolesClaim;
    }

    public void setRolesClaim(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public Map<String, SocleRole> getRoleMapping() {
        return roleMapping;
    }

    public void setRoleMapping(Map<String, SocleRole> roleMapping) {
        this.roleMapping = roleMapping != null ? roleMapping : defaultRoleMapping();
    }

    public SocleRole getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(SocleRole defaultRole) {
        this.defaultRole = defaultRole != null ? defaultRole : SocleRole.CONTRIBUTEUR;
    }

    public RoleSource getRoleSource() {
        return roleSource;
    }

    public void setRoleSource(RoleSource roleSource) {
        this.roleSource = roleSource != null ? roleSource : RoleSource.CLAIMS;
    }

    public List<String> getBootstrapAdminSubjects() {
        return bootstrapAdminSubjects;
    }

    public void setBootstrapAdminSubjects(List<String> bootstrapAdminSubjects) {
        this.bootstrapAdminSubjects = bootstrapAdminSubjects != null ? bootstrapAdminSubjects : new ArrayList<>();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes != null ? scopes : new ArrayList<>(List.of("openid", "profile", "email"));
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public void setAuthorizationEndpoint(String authorizationEndpoint) {
        this.authorizationEndpoint = authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getEndSessionEndpoint() {
        return endSessionEndpoint;
    }

    public void setEndSessionEndpoint(String endSessionEndpoint) {
        this.endSessionEndpoint = endSessionEndpoint;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public boolean isLinkByVerifiedEmail() {
        return linkByVerifiedEmail;
    }

    public void setLinkByVerifiedEmail(boolean linkByVerifiedEmail) {
        this.linkByVerifiedEmail = linkByVerifiedEmail;
    }

    public AccessPolicy getAccessPolicy() {
        return accessPolicy;
    }

    public void setAccessPolicy(AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy != null ? accessPolicy : new AccessPolicy();
    }

    public String getPasskeyAcrValues() {
        return passkeyAcrValues;
    }

    public void setPasskeyAcrValues(String passkeyAcrValues) {
        this.passkeyAcrValues = passkeyAcrValues != null ? passkeyAcrValues : "";
    }

    public String getIdpDisplayName() {
        return idpDisplayName;
    }

    public void setIdpDisplayName(String idpDisplayName) {
        this.idpDisplayName = idpDisplayName != null ? idpDisplayName : "";
    }

    public String getSupportContact() {
        return supportContact;
    }

    public void setSupportContact(String supportContact) {
        this.supportContact = supportContact != null ? supportContact : "";
    }
}
