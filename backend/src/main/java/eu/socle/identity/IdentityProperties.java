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
}
