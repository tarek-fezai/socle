// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Mode relational après un historique Git : refuse le démarrage si des documents
 * actifs portent encore un {@code git_head_sha} mais un {@code body} JSONB vide /
 * inexploitable — cas où la lecture relational n'aurait plus de source de vérité.
 *
 * <p>En fonctionnement normal, {@code DocumentService} maintient {@code documents.body}
 * à chaque create/update/restore (projection Search = même colonne). Ce validateur
 * attrape l'incohérence résiduelle (migration manuelle, écriture hors API).
 */
@Component
@ConditionalOnProperty(name = "socle.storage.provider", havingValue = "relational")
public class RelationalStorageConsistencyValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RelationalStorageConsistencyValidator.class);

    private final JdbcTemplate jdbc;

    public RelationalStorageConsistencyValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer orphans;
        try {
            orphans = jdbc.queryForObject("""
                    SELECT count(*) FROM documents
                     WHERE deleted_at IS NULL
                       AND git_head_sha IS NOT NULL
                       AND btrim(git_head_sha) <> ''
                       AND (
                         body IS NULL
                         OR body = 'null'::jsonb
                         OR body = '{}'::jsonb
                         OR jsonb_typeof(body) <> 'object'
                       )
                    """, Integer.class);
        } catch (Exception e) {
            log.debug("Relational consistency check skipped: {}", e.getMessage());
            return;
        }
        if (orphans != null && orphans > 0) {
            throw new IllegalStateException(
                    "socle.storage.provider=relational mais " + orphans
                            + " document(s) actif(s) ont un git_head_sha sans body JSONB exploitable. "
                            + "Bascule git→relational interdite sans rematérialiser le contenu "
                            + "dans documents.body (pas de lecture silencieuse depuis un dépôt Git "
                            + "désactivé).");
        }
    }
}
