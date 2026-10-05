// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.testsupport;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Postgres Testcontainers migré avec les <strong>vraies</strong> migrations Flyway (V1…Vn) : valide le SQL
 * livré (contraintes, triggers) plutôt qu'un schéma recopié dans le test.
 */
public final class MigratedPostgres {

    private MigratedPostgres() {}

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("socle_core")
                .withUsername("socle")
                .withPassword("socle");
    }

    public static DataSource dataSource(PostgreSQLContainer<?> pg) {
        return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /** Applique toutes les migrations de {@code classpath:db/migration} et renvoie un JdbcTemplate. */
    public static JdbcTemplate migrate(PostgreSQLContainer<?> pg) {
        DataSource ds = dataSource(pg);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }
}
