// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Set;

/**
 * Choix d'instance exclusif — pas de défaut silencieux, pas de mixte par espace.
 */
@Component
@ConfigurationProperties(prefix = "socle.storage")
public class StorageProperties {

    private static final Set<String> ALLOWED = Set.of("relational", "git");

    /** {@code relational} | {@code git} — obligatoire. */
    private String provider;

    /** Chemin local du dépôt Git (mode git uniquement). */
    private String gitRepositoryPath = "./data/git-content";

    @PostConstruct
    void validate() {
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(
                    "socle.storage.provider est obligatoire (relational|git) — aucun défaut silencieux");
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalStateException(
                    "socle.storage.provider invalide: '" + provider + "' (attendu: relational|git)");
        }
        this.provider = normalized;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getGitRepositoryPath() {
        return gitRepositoryPath;
    }

    public void setGitRepositoryPath(String gitRepositoryPath) {
        this.gitRepositoryPath = gitRepositoryPath;
    }

    public boolean isGit() {
        return "git".equals(provider);
    }

    public boolean isRelational() {
        return "relational".equals(provider);
    }
}
