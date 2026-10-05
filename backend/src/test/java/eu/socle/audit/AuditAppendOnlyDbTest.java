// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie en base réelle que UPDATE/DELETE sur {@code audit_log_events}
 * sont refusés au rôle applicatif non-superuser {@code socle_app}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class AuditAppendOnlyDbTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("postgres")
            .withPassword("postgres");

    /** Connexion en tant que rôle applicatif (comme le backend). */
    static JdbcTemplate appJdbc;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        admin.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY,
                  email TEXT NOT NULL,
                  display_name TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'active',
                  is_system_account BOOLEAN NOT NULL DEFAULT false,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        admin.execute("""
                CREATE TABLE audit_log_events (
                  id BIGSERIAL PRIMARY KEY,
                  actor_id UUID REFERENCES users(id),
                  actor_is_system BOOLEAN NOT NULL DEFAULT false,
                  action TEXT NOT NULL,
                  resource_type TEXT NOT NULL,
                  resource_id UUID,
                  metadata JSONB,
                  ip_address INET,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        // Rôle applicatif non-superuser (POSTGRES_USER est superuser → REVOKE inefficace sur lui)
        admin.execute("CREATE ROLE socle_app LOGIN PASSWORD 'socle_app' NOSUPERUSER");
        admin.execute("GRANT CONNECT ON DATABASE socle_core TO socle_app");
        admin.execute("GRANT USAGE ON SCHEMA public TO socle_app");
        admin.execute("REVOKE UPDATE, DELETE ON TABLE audit_log_events FROM PUBLIC");
        admin.execute("REVOKE ALL ON TABLE audit_log_events FROM socle_app");
        admin.execute("GRANT SELECT, INSERT ON TABLE audit_log_events TO socle_app");
        admin.execute("GRANT USAGE, SELECT ON SEQUENCE audit_log_events_id_seq TO socle_app");

        admin.update("""
                INSERT INTO audit_log_events (actor_is_system, action, resource_type, resource_id, metadata)
                VALUES (true, 'access.granted', 'space', '00000000-0000-0000-0000-000000000001', '{}'::jsonb)
                """);

        String appUrl = postgres.getJdbcUrl();
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(appUrl, "socle_app", "socle_app"));
    }

    @Test
    void updateIsRejectedForAppRole() {
        assertThatThrownBy(() -> appJdbc.update("UPDATE audit_log_events SET action = 'tampered'"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deleteIsRejectedForAppRole() {
        assertThatThrownBy(() -> appJdbc.update("DELETE FROM audit_log_events"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void insertAndSelectRemainAllowed() {
        appJdbc.update("""
                INSERT INTO audit_log_events (actor_is_system, action, resource_type, metadata)
                VALUES (false, 'document.created', 'document', '{}'::jsonb)
                """);
        Integer count = appJdbc.queryForObject("SELECT count(*) FROM audit_log_events", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(2);
    }
}
