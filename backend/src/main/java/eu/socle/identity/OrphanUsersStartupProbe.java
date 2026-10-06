// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Diagnostic au démarrage : compte les utilisateurs sans aucune ligne
 * {@code user_identities}. N'attache rien automatiquement (liaison explicite uniquement).
 */
@Component
@Order(100)
public class OrphanUsersStartupProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanUsersStartupProbe.class);

    private final JdbcTemplate jdbc;

    public OrphanUsersStartupProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long orphans = jdbc.queryForObject("""
                    SELECT count(*) FROM users u
                     WHERE NOT EXISTS (
                           SELECT 1 FROM user_identities i WHERE i.user_id = u.id
                     )
                    """, Long.class);
            long n = orphans == null ? 0 : orphans;
            if (n > 0) {
                log.warn(
                        "Identité : {} utilisateur(s) sans aucune user_identities "
                                + "(aucune liaison automatique — rattacher via admin/import)",
                        n);
            } else {
                log.debug("Identité : aucun utilisateur orphelin (sans user_identities)");
            }
        } catch (Exception e) {
            // Schéma pas encore migré (tests unitaires sans Flyway) — ne pas bloquer.
            log.debug("Identité : probe orphelins ignorée : {}", e.getMessage());
        }
    }
}
