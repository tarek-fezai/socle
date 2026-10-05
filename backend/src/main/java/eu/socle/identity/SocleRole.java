// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

/**
 * Rôles plateforme Socle — indépendants de l'IdP.
 *
 * <p>Les autorités Spring restent {@code ROLE_} + nom Keycloak historique
 * ({@code contributeur}, {@code auditeur}, …) pour ne pas casser
 * {@code hasRole(...)} ni les tests SoD existants.
 */
public enum SocleRole {
    CONTRIBUTEUR("contributeur"),
    AUDITEUR("auditeur"),
    INTEGRATEUR("integrateur"),
    ADMINISTRATEUR_SYSTEME("administrateur-systeme");

    private final String springRole;

    SocleRole(String springRole) {
        this.springRole = springRole;
    }

    /** Nom de rôle Spring Security (sans préfixe {@code ROLE_}). */
    public String springRole() {
        return springRole;
    }

    /** Autorité Spring complète ({@code ROLE_contributeur}, …). */
    public String authority() {
        return "ROLE_" + springRole;
    }
}
