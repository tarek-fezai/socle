// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attestation;

import org.junit.jupiter.api.BeforeAll;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V33 applique sur des données V1 (tables héritées) : rétro-remplissage de l'audience,
 * rattachement des accusés à la campagne, une seule campagne ouverte par document.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class AttestationMigrationV33Test {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID U1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    static final UUID U2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    static final UUID GROUP = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC_A = UUID.fromString("11111111-1111-1111-1111-1111111111a1");
    static final UUID DOC_B = UUID.fromString("11111111-1111-1111-1111-1111111111b1");
    static final UUID DOC_C = UUID.fromString("11111111-1111-1111-1111-1111111111c1");
    static final UUID OLD_CAMPAIGN = UUID.fromString("55555555-5555-5555-5555-555555555501");
    static final UUID NEW_CAMPAIGN = UUID.fromString("55555555-5555-5555-5555-555555555502");
    static final UUID GROUPLESS_CAMPAIGN = UUID.fromString("55555555-5555-5555-5555-555555555503");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void migrate() throws Exception {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL)
                """);
        jdbc.execute("CREATE TABLE groups (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  PRIMARY KEY (group_id, user_id)
                )
                """);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, current_version_no INT NOT NULL DEFAULT 1
                )
                """);
        jdbc.execute("""
                CREATE TABLE attestation_acknowledgments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  UNIQUE (document_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE attestation_campaigns (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  target_group_id UUID REFERENCES groups(id),
                  due_date DATE NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'u1@x', 'u1'), (?, 'u2@x', 'u2')",
                U1, U2);
        jdbc.update("INSERT INTO groups (id, name) VALUES (?, 'G')", GROUP);
        jdbc.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?), (?, ?)", GROUP, U1, GROUP, U2);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title, current_version_no) VALUES "
                + "(?, ?, 'A', 5), (?, ?, 'B', 2), (?, ?, 'C', 1)",
                DOC_A, SPACE, DOC_B, SPACE, DOC_C, SPACE);

        // DOC_A : deux campagnes héritées (la plus récente doit rester ouverte).
        jdbc.update("""
                INSERT INTO attestation_campaigns (id, document_id, target_group_id, due_date, created_at)
                VALUES (?, ?, ?, '2026-01-01', '2026-01-01T00:00:00Z'),
                       (?, ?, ?, '2026-06-01', '2026-02-01T00:00:00Z')
                """, OLD_CAMPAIGN, DOC_A, GROUP, NEW_CAMPAIGN, DOC_A, GROUP);
        // DOC_B : campagne sans groupe cible.
        jdbc.update("""
                INSERT INTO attestation_campaigns (id, document_id, target_group_id, due_date)
                VALUES (?, ?, NULL, '2026-06-01')
                """, GROUPLESS_CAMPAIGN, DOC_B);
        // Accusés : DOC_A rattaché ; DOC_C sans campagne (orphelin).
        jdbc.update("INSERT INTO attestation_acknowledgments (document_id, user_id) VALUES (?, ?), (?, ?)",
                DOC_A, U1, DOC_C, U2);

        jdbc.execute(new ClassPathResource("db/migration/V33__attestation_campaigns.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void campaigns_areBackfilled() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM attestation_campaigns WHERE id = ?", NEW_CAMPAIGN);
        assertThat(row.get("audience_type")).isEqualTo("group");
        assertThat(row.get("audience_ref")).isEqualTo(GROUP);
        assertThat(row.get("audience_size")).isEqualTo(2);
        assertThat(row.get("version_no")).isEqualTo(5);
        assertThat(row).doesNotContainKey("target_group_id");

        Map<String, Object> groupless = jdbc.queryForMap(
                "SELECT * FROM attestation_campaigns WHERE id = ?", GROUPLESS_CAMPAIGN);
        assertThat(groupless.get("audience_type")).isEqualTo("space_members");
        assertThat(groupless.get("audience_ref")).isNull();
        assertThat(groupless.get("audience_size")).isEqualTo(0);
    }

    @Test
    void onlyLatestCampaignStaysOpenPerDocument() {
        List<UUID> open = jdbc.query(
                "SELECT id FROM attestation_campaigns WHERE document_id = ? AND closed_at IS NULL",
                (rs, i) -> (UUID) rs.getObject("id"), DOC_A);
        assertThat(open).containsExactly(NEW_CAMPAIGN);
    }

    @Test
    void acknowledgments_areAttachedToLatestCampaign_andOrphansDropped() {
        List<Map<String, Object>> acks = jdbc.queryForList(
                "SELECT campaign_id, user_id, version_no FROM attestation_acknowledgments");
        assertThat(acks).hasSize(1);
        assertThat(acks.getFirst().get("campaign_id")).isEqualTo(NEW_CAMPAIGN);
        assertThat(acks.getFirst().get("user_id")).isEqualTo(U1);
        assertThat(acks.getFirst().get("version_no")).isEqualTo(5);
    }

    @Test
    void legacyDocumentColumn_isGone() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'attestation_acknowledgments' AND column_name = 'document_id'
                """, Integer.class);
        assertThat(n).isZero();
    }
}
