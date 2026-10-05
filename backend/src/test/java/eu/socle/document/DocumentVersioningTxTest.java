// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie qu'un rollback de l'update document n'insère pas de version orpheline
 * (même transaction Postgres).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class DocumentVersioningTxTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY,
                  email TEXT NOT NULL,
                  display_name TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'active'
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY,
                  name TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL,
                  body JSONB NOT NULL,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  current_version_no INTEGER NOT NULL DEFAULT 1,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_versions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  version_no INTEGER NOT NULL,
                  body_snapshot JSONB NOT NULL,
                  author_id UUID REFERENCES users(id),
                  change_summary TEXT,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  UNIQUE (document_id, version_no)
                )
                """);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'u@x.com', 'U')", USER);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Default')", SPACE);
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, current_version_no)
                VALUES (?, ?, 'Doc', '{"v":1}'::jsonb, 1)
                """, DOC, SPACE);
    }

    @Test
    void rollbackAfterVersionInsert_leavesNoOrphanVersion() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO document_versions (document_id, version_no, body_snapshot, author_id, change_summary)
                    VALUES (?, 1, '{"v":1}'::jsonb, ?, 'edit')
                    """, DOC, USER);
            // Simule l'échec de l'UPDATE documents dans la même transaction
            throw new RuntimeException("simulated update failure");
        })).hasMessageContaining("simulated update failure");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document_versions WHERE document_id = ?",
                Integer.class, DOC);
        assertThat(count).isZero();

        Integer versionNo = jdbc.queryForObject(
                "SELECT current_version_no FROM documents WHERE id = ?",
                Integer.class, DOC);
        assertThat(versionNo).isEqualTo(1);
    }

    @Test
    void successfulUpdate_persistsVersionAndIncrements() {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO document_versions (document_id, version_no, body_snapshot, author_id, change_summary)
                    VALUES (?, 1, '{"v":1}'::jsonb, ?, 'edit')
                    """, DOC, USER);
            jdbc.update("""
                    UPDATE documents SET body = '{"v":2}'::jsonb, current_version_no = 2, updated_at = now()
                     WHERE id = ?
                    """, DOC);
        });

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document_versions WHERE document_id = ? AND version_no = 1",
                Integer.class, DOC);
        assertThat(count).isEqualTo(1);
        Integer versionNo = jdbc.queryForObject(
                "SELECT current_version_no FROM documents WHERE id = ?",
                Integer.class, DOC);
        assertThat(versionNo).isEqualTo(2);
    }
}
