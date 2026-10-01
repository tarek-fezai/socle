// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Mode Git : refuse le démarrage si des documents actifs existent sans
 * {@code git_head_sha} (typiquement après bascule relational→git sans migration).
 * Évite le silence dangereux où la lecture retomberait sur la projection Postgres.
 */
@Component
@ConditionalOnProperty(name = "socle.storage.provider", havingValue = "git")
public class GitStorageConsistencyValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GitStorageConsistencyValidator.class);

    private final JdbcTemplate jdbc;

    public GitStorageConsistencyValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer orphans;
        try {
            orphans = jdbc.queryForObject("""
                    SELECT count(*) FROM documents
                     WHERE deleted_at IS NULL
                       AND (git_head_sha IS NULL OR btrim(git_head_sha) = '')
                    """, Integer.class);
        } catch (Exception e) {
            // Schéma pas encore migré (tests unitaires sans Flyway) — ne pas bloquer.
            log.debug("Git consistency check skipped: {}", e.getMessage());
            return;
        }
        if (orphans != null && orphans > 0) {
            throw new IllegalStateException(
                    "socle.storage.provider=git mais " + orphans
                            + " document(s) actif(s) sans git_head_sha. "
                            + "Bascule relational→git interdite sans migration explicite du contenu "
                            + "vers le dépôt Git (pas de fallback silencieux).");
        }
    }
}
