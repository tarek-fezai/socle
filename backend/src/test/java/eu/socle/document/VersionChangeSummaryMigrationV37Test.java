// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V37 décale les change_summary (même schéma PG pour relational et git).
 * Inclut les documents en corbeille ; no-op si le marqueur existe déjà.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class VersionChangeSummaryMigrationV37Test {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC_ACTIVE = UUID.fromString("11111111-1111-1111-1111-1111111111a1");
    static final UUID DOC_TRASH = UUID.fromString("11111111-1111-1111-1111-1111111111b1");
    static final UUID DOC_MARKER = UUID.fromString("11111111-1111-1111-1111-1111111111c1");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}'::jsonb,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  current_version_no INT NOT NULL DEFAULT 1,
                  current_change_summary TEXT,
                  deleted_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_versions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  version_no INTEGER NOT NULL,
                  body_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
                  change_summary TEXT,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  UNIQUE (document_id, version_no)
                )
                """);
        jdbc.execute("""
                CREATE TABLE authz_migrations (
                  name TEXT PRIMARY KEY,
                  applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  report JSONB
                )
                """);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM authz_migrations");
    }

    @Test
    void v37_shiftsOldOffset_includingTrashedDocuments() throws Exception {
        // Même lignes PG que git ou relational : ancien décalage résumé de N+1 sur N.
        seedDoc(DOC_ACTIVE, null, 4, null, "S2", "S3", "S4");
        seedDoc(DOC_TRASH, Instant.parse("2026-01-01T00:00:00Z"), 3, null, "T2", "T3");

        System.out.println("V37 BEFORE active=" + summarize(DOC_ACTIVE)
                + " trash=" + summarize(DOC_TRASH));

        applyV37();

        System.out.println("V37 AFTER  active=" + summarize(DOC_ACTIVE)
                + " trash=" + summarize(DOC_TRASH));

        assertSummaries(DOC_ACTIVE, null, "S2", "S3", "S4");
        assertSummaries(DOC_TRASH, null, "T2", "T3");

        Map<String, Object> marker = jdbc.queryForMap(
                "SELECT name, report FROM authz_migrations WHERE name = ?",
                "version-change-summary-v1");
        assertThat(marker.get("name")).isEqualTo("version-change-summary-v1");
        assertThat(String.valueOf(marker.get("report"))).contains("V37");
    }

    @Test
    void v37_whenMarkerExists_isNoOp() throws Exception {
        seedDoc(DOC_MARKER, null, 3, "already-current", null, "keep-v2");
        jdbc.update("""
                INSERT INTO authz_migrations (name, applied_at, report)
                VALUES ('version-change-summary-v1', now(), '{"source":"runner"}'::jsonb)
                """);

        String before = summarize(DOC_MARKER);
        applyV37();
        String after = summarize(DOC_MARKER);

        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT report->>'source' FROM authz_migrations WHERE name = ?",
                String.class, "version-change-summary-v1")).isEqualTo("runner");
    }

    private static void applyV37() throws Exception {
        jdbc.execute(new ClassPathResource("db/migration/V37__version_change_summary_shift.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static void seedDoc(
            UUID id,
            Instant deletedAt,
            int currentVersionNo,
            String currentSummary,
            String... archivedSummariesOldestFirst
    ) {
        jdbc.update("""
                INSERT INTO documents
                  (id, space_id, title, current_version_no, current_change_summary, deleted_at)
                VALUES (?, ?, 'D', ?, ?, ?)
                """,
                id, SPACE, currentVersionNo, currentSummary,
                deletedAt == null ? null : Timestamp.from(deletedAt));
        for (int i = 0; i < archivedSummariesOldestFirst.length; i++) {
            jdbc.update("""
                    INSERT INTO document_versions (document_id, version_no, body_snapshot, change_summary)
                    VALUES (?, ?, '{}'::jsonb, ?)
                    """, id, i + 1, archivedSummariesOldestFirst[i]);
        }
    }

    /** attendu = résumés archivés (v1…vN) puis résumé courant. */
    private static void assertSummaries(UUID docId, String... expectedOldestFirstThenCurrent) {
        List<String> archived = jdbc.query(
                "SELECT change_summary FROM document_versions WHERE document_id = ? ORDER BY version_no",
                (rs, i) -> rs.getString(1), docId);
        String current = jdbc.queryForObject(
                "SELECT current_change_summary FROM documents WHERE id = ?", String.class, docId);
        assertThat(archived).hasSize(expectedOldestFirstThenCurrent.length - 1);
        for (int i = 0; i < archived.size(); i++) {
            assertThat(archived.get(i)).as("v" + (i + 1)).isEqualTo(expectedOldestFirstThenCurrent[i]);
        }
        assertThat(current).as("current").isEqualTo(
                expectedOldestFirstThenCurrent[expectedOldestFirstThenCurrent.length - 1]);
    }

    private static String summarize(UUID docId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version_no, change_summary FROM document_versions WHERE document_id = ? ORDER BY version_no",
                docId);
        String current = jdbc.queryForObject(
                "SELECT current_change_summary FROM documents WHERE id = ?", String.class, docId);
        return "archived=" + rows + " current=" + current;
    }
}
