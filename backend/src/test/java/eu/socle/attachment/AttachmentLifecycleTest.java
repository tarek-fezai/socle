// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.blob.LocalBlobStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle de vie : référence sticky (v-1), purge document, purge orpheline.
 */
@ExtendWith(MockitoExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class AttachmentLifecycleTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TempDir Path blobDir;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;

    JdbcTemplate jdbc;
    LocalBlobStore blobStore;
    AttachmentProperties properties;
    AttachmentService service;
    Clock clock;

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id UUID PRIMARY KEY,
                  email TEXT NOT NULL,
                  display_name TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'active'
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                  id UUID PRIMARY KEY,
                  title TEXT NOT NULL DEFAULT 'Doc',
                  deleted_at TIMESTAMPTZ NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS approval_requests (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL,
                  status TEXT NOT NULL
                )
                """);
        Integer att = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'attachments'",
                Integer.class);
        if (att == null || att == 0) {
            ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V39__attachments.sql"));
            pop.setSqlScriptEncoding("UTF-8");
            pop.execute(ds);
        }
        jdbc.update("DELETE FROM attachments");
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                USER, "u@example.com", "U");
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, 'Doc')", DOC);

        blobStore = new LocalBlobStore(blobDir);
        properties = new AttachmentProperties();
        properties.setOrphanRetentionDays(7);
        clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
        service = new AttachmentService(
                jdbc, blobStore, userSyncService, authorizationService, auditService,
                properties, new MediaTypeDetector(), new ImageSanitizer(), clock);

        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setEmail("u@example.com");
        user.setDisplayName("U");
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        lenient().when(authorizationService.hasRelation(eq(USER), eq("document"), eq(DOC), eq("viewer")))
                .thenReturn(true);
    }

    @Test
    void referencedInArchivedVersion_stillServed_afterRemovalFromCurrent() throws Exception {
        Jwt jwt = jwt();
        var uploaded = service.upload(jwt, DOC, txtFile("note.txt", "hello"));
        Map<String, Object> body = attachmentBody(uploaded.id());
        service.markReferenced(DOC, body);

        // Still referenced (sticky) even if current body no longer cites it
        service.markReferenced(DOC, Map.of("type", "doc", "content", List.of()));

        try (var content = service.openForDownload(jwt, uploaded.id(), Optional.empty())) {
            assertThat(content.row().id()).isEqualTo(uploaded.id());
            assertThat(content.content().readAllBytes())
                    .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
        Integer orphans = jdbc.queryForObject(
                "SELECT count(*) FROM attachments WHERE id = ? AND referenced_at IS NOT NULL",
                Integer.class, uploaded.id());
        assertThat(orphans).isEqualTo(1);
    }

    @Test
    void documentPurge_deletesBlobAndAudits() throws Exception {
        Jwt jwt = jwt();
        var uploaded = service.upload(jwt, DOC, txtFile("a.txt", "x"));
        String key = jdbc.queryForObject(
                "SELECT storage_key::text FROM attachments WHERE id = ?", String.class, uploaded.id());
        assertThat(blobStore.exists(key)).isTrue();

        int n = service.purgeForDocument(DOC, null, true);
        assertThat(n).isEqualTo(1);
        assertThat(blobStore.exists(key)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attachments WHERE id = ?", Integer.class, uploaded.id()))
                .isZero();

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditService, atLeastOnce()).record(
                isNull(), eq(true), action.capture(), eq("attachment"), eq(uploaded.id()),
                anyMap(), isNull());
        assertThat(action.getAllValues()).contains(AuditActions.ATTACHMENT_PURGED);
    }

    @Test
    void orphan_purgedAfterRetention() throws Exception {
        Jwt jwt = jwt();
        // Upload at "now" — not yet purgeable
        var fresh = service.upload(jwt, DOC, txtFile("fresh.txt", "f"));
        assertThat(service.purgeOrphans()).isZero();

        // Backdate created_at beyond retention
        jdbc.update(
                "UPDATE attachments SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")),
                fresh.id());
        String key = jdbc.queryForObject(
                "SELECT storage_key::text FROM attachments WHERE id = ?", String.class, fresh.id());

        assertThat(service.purgeOrphans()).isEqualTo(1);
        assertThat(blobStore.exists(key)).isFalse();
        verify(auditService, atLeastOnce()).record(
                isNull(), eq(true), eq(AuditActions.ATTACHMENT_PURGED),
                eq("attachment"), eq(fresh.id()), anyMap(), isNull());
    }

    private static MockMultipartFile txtFile(String name, String body) {
        return new MockMultipartFile(
                "file", name, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> attachmentBody(UUID id) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", id.toString());
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "attachment");
        node.put("attrs", attrs);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(node));
        return doc;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .claim("email", "u@example.com")
                .build();
    }
}
