// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Exécute {@code team-roles-v1} avant d'accepter des requêtes
 * ({@link SmartLifecycle} phase {@code -10}, après {@code OpenFgaBootstrap} {@code -20})
 * et de nouveau via {@link ApplicationRunner} si le cycle de vie n'a pas déjà échoué.
 *
 * <p>Échec → exception (démarrage refusé). Désactivable uniquement en tests
 * ({@code socle.openfga.team-roles-migration-on-startup=false}).
 */
@Component
@Order(2)
@ConditionalOnProperty(
        name = "socle.openfga.team-roles-migration-on-startup",
        havingValue = "true",
        matchIfMissing = true
)
public class TeamRolesTupleMigrationRunner implements ApplicationRunner, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TeamRolesTupleMigrationRunner.class);

    private final TeamRolesTupleMigrationService service;
    private volatile boolean running;

    public TeamRolesTupleMigrationRunner(TeamRolesTupleMigrationService service) {
        this.service = service;
    }

    @Override
    public void start() {
        runMigration("SmartLifecycle");
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -10;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void run(ApplicationArguments args) {
        runMigration("ApplicationRunner");
    }

    private void runMigration(String via) {
        log.info("FGA team-roles-v1 au démarrage ({})", via);
        service.migrateAtStartup();
    }
}
