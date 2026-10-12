// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Liste admin de 200 utilisateurs : nombre de requêtes borné (pas de N+1).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class DirectoryAdminListPerfTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static CountingDataSource counting;
    static JdbcTemplate jdbc;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void seed() {
        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        counting = new CountingDataSource(raw);
        jdbc = new JdbcTemplate(counting);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL,
                  status TEXT NOT NULL, avatar_initials TEXT, last_login_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("CREATE TABLE user_platform_roles (user_id UUID NOT NULL, role TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE groups (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE group_members (group_id UUID NOT NULL, user_id UUID NOT NULL, role TEXT NOT NULL)");
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO groups (id, name) VALUES (?, ?)", team, "Équipe");
        for (int i = 0; i < 200; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?)",
                    id, "u" + i + "@ex.com", "User " + String.format("%03d", i), "active");
            jdbc.update("INSERT INTO user_platform_roles (user_id, role) VALUES (?, ?)", id, "CONTRIBUTEUR");
            jdbc.update("INSERT INTO group_members (group_id, user_id, role) VALUES (?,?,?)",
                    team, id, "contributeur");
        }
    }

    @Test
    void twoHundredUsers_fixedQueryCount() {
        DirectoryProperties props = new DirectoryProperties();
        DirectoryService service = new DirectoryService(jdbc, props, Clock.systemUTC());
        counting.connections.set(0);
        long t0 = System.nanoTime();
        var page = service.listAdmin(null, null, null, null, 0, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(page.users()).hasSize(200);
        assertThat(page.users().get(0).teams()).isNotEmpty();
        assertThat(page.users().get(0).platformRoles()).isNotEmpty();
        // 3 agrégats + 1 page + 1 rôles ANY + 1 équipes ANY — indépendant de N
        assertThat(counting.connections.get())
                .as("connexions (proxy des statements) pour 200 users : %s en %d ms", counting.connections.get(), ms)
                .isLessThanOrEqualTo(12);
        System.out.printf("admin list 200 users: %d ms, %d connections%n", ms, counting.connections.get());
    }

    static final class CountingDataSource extends DelegatingDataSource {
        final AtomicInteger connections = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return super.getConnection();
        }
    }
}
