// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import com.jayway.jsonpath.JsonPath;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.blob.BlobKeys;
import eu.socle.blob.BlobStore;
import eu.socle.blob.LocalBlobStore;
import eu.socle.config.SecurityWebMvcTest;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.web.ApiErrors;
import eu.socle.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sécurité des pièces jointes de bout en bout (hors Keycloak/OpenFGA) : vrai
 * {@link AttachmentService} + {@link LocalBlobStore} + Postgres (Testcontainers) derrière
 * {@code SecurityConfig} ; l'autorisation (OpenFGA), la synchro utilisateur et l'audit sont mockés.
 */
@SecurityWebMvcTest(controllers = {AttachmentController.class, DocumentAttachmentController.class})
// AttachmentService réel, autowiré par Spring (garde-fou : 2 constructeurs → @Autowired requis).
@Import({ApiExceptionHandler.class, AttachmentService.class, MediaTypeDetector.class, ImageSanitizer.class})
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class AttachmentSecurityWebMvcTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final int MAX_MB = 1;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static Path blobDir;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }

        @Bean
        BlobStore blobStore() {
            return new LocalBlobStore(blobDir);
        }

        @Bean
        AttachmentProperties attachmentProperties() {
            AttachmentProperties p = new AttachmentProperties();
            p.setMaxMb(MAX_MB);
            p.setMaxVideoMb(MAX_MB);
            return p;
        }
    }

    @MockBean UserSyncService userSyncService;
    @MockBean AuthorizationService authorizationService;
    @MockBean AuditService auditService;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlobStore blobStore;

    @BeforeAll
    static void schema() throws IOException {
        blobDir = Files.createTempDirectory("socle-attachments-it");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'active'
                )
                """);
        admin.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        admin.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id),
                  status TEXT NOT NULL DEFAULT 'en_cours'
                )
                """);
        // Migration réelle V39 (table attachments + contraintes).
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V39__attachments.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        admin.update("INSERT INTO users (id, email, display_name) VALUES (?, 'u@example.com', 'U')", USER);
        admin.update("INSERT INTO documents (id, title) VALUES (?, 'Doc')", DOC);
    }

    @AfterAll
    static void cleanBlobDir() throws IOException {
        if (blobDir != null) {
            try (var files = Files.walk(blobDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        try (var files = Files.list(blobDir)) {
            files.filter(Files::isRegularFile).forEach(p -> p.toFile().delete());
        }
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM attachments");
        UserEntity u = new UserEntity();
        u.setId(USER);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        // Par défaut : éditeur ET viewer (requireDocumentRelation ne lève pas, hasRelation → true).
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(true);
    }

    // ── POST : authn / authz / état du document ────────────────────────────────────────────

    @Test
    void upload_withoutAuthentication_is401() throws Exception {
        mockMvc.perform(upload("a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_withoutEditorRelation_is403_andNothingStored() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        mockMvc.perform(upload("a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)).with(jwt()))
                .andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
    }

    @Test
    void upload_whileApprovalInProgress_is409_approvalInProgress() throws Exception {
        jdbc.update("INSERT INTO approval_requests (document_id, status) VALUES (?, 'en_cours')", DOC);

        mockMvc.perform(upload("a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)).with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.APPROVAL_IN_PROGRESS));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
    }

    @Test
    void upload_afterApprovalFinished_isAccepted() throws Exception {
        jdbc.update("INSERT INTO approval_requests (document_id, status) VALUES (?, 'approuve')", DOC);

        mockMvc.perform(upload("a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)).with(jwt()))
                .andExpect(status().isCreated());
    }

    // ── POST : type MIME (détection par octets, jamais par nom/Content-Type client) ────────

    @Test
    void upload_htmlDisguisedAsPng_is415_attachmentTypeRejected() throws Exception {
        byte[] html = "<!DOCTYPE html><html><body><script>alert(document.cookie)</script></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(upload("photo.png", "image/png", html).with(jwt()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiErrors.ATTACHMENT_TYPE_REJECTED));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).as("aucun blob orphelin").isZero();
    }

    @Test
    void upload_pngMagicFollowedByHtml_isRejected_andNotStored() throws Exception {
        // Polyglotte : signature PNG valide, puis HTML — pas décodable comme image → refusé.
        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = new byte[pngSignature.length + html.length];
        System.arraycopy(pngSignature, 0, polyglot, 0, pngSignature.length);
        System.arraycopy(html, 0, polyglot, pngSignature.length, html.length);

        mockMvc.perform(upload("photo.png", "image/png", polyglot).with(jwt()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiErrors.ATTACHMENT_TYPE_REJECTED));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).isZero();
    }

    @Test
    void upload_svg_is415_evenWhenNamedPngAndDeclaredPng() throws Exception {
        byte[] svg = ("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(upload("logo.svg", "image/svg+xml", svg).with(jwt()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiErrors.ATTACHMENT_TYPE_REJECTED));

        mockMvc.perform(upload("logo.png", "image/png", svg).with(jwt()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiErrors.ATTACHMENT_TYPE_REJECTED));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).isZero();
    }

    // ── POST : taille ──────────────────────────────────────────────────────────────────────

    @Test
    void upload_overLimit_is413_payloadTooLarge() throws Exception {
        byte[] big = new byte[MAX_MB * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');

        mockMvc.perform(upload("big.txt", "text/plain", big).with(jwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAYLOAD_TOO_LARGE));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).isZero();
    }

    @Test
    void upload_exactlyAtLimit_isAccepted() throws Exception {
        byte[] atLimit = new byte[MAX_MB * 1024 * 1024];
        java.util.Arrays.fill(atLimit, (byte) 'a');

        mockMvc.perform(upload("limit.txt", "text/plain", atLimit).with(jwt()))
                .andExpect(status().isCreated());
    }

    // ── POST : EXIF / GPS ──────────────────────────────────────────────────────────────────

    @Test
    void upload_truncatedWebp_is415_attachmentTypeRejected_andNotStored() throws Exception {
        byte[] truncated = TestImages.truncatedWebp();
        assertThat(truncated.length).isEqualTo(33);

        mockMvc.perform(upload("broken.webp", "image/webp", truncated).with(jwt()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.ATTACHMENT_TYPE_REJECTED));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).as("aucun blob orphelin").isZero();
    }

    @Test
    void upload_jpegWithGpsExif_storedBytesHaveNoExif() throws Exception {
        byte[] original = TestImages.jpegWithGpsExif(16, 12);
        assertThat(TestImages.contains(original, TestImages.GPS_MARKER)).as("fixture").isTrue();

        MvcResult res = mockMvc.perform(upload("photo.jpg", "image/jpeg", original).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.width").value(16))
                .andExpect(jsonPath("$.height").value(12))
                .andReturn();

        String id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
        String sha = JsonPath.read(res.getResponse().getContentAsString(), "$.sha256");
        byte[] stored = storedBytes(UUID.fromString(id));

        assertThat(stored).isNotEqualTo(original);
        assertThat(TestImages.hasApp1Segment(stored)).as("segment APP1 (EXIF/XMP)").isFalse();
        assertThat(TestImages.contains(stored, "Exif")).isFalse();
        assertThat(TestImages.contains(stored, TestImages.GPS_MARKER)).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).as("toujours une image valide").isNotNull();
        // Le sha256 exposé est celui des octets réellement stockés (pas de l'original).
        assertThat(sha).isEqualTo(sha256Hex(stored)).isNotEqualTo(sha256Hex(original));

        // Et ce que le client télécharge est bien la version nettoyée.
        byte[] downloaded = mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(stored);
    }

    // ── GET : visibilité ───────────────────────────────────────────────────────────────────

    @Test
    void get_withoutAuthentication_is401() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_nonViewer_is404_notForbidden() throws Exception {
        UUID id = uploadText("secret.txt", "contenu confidentiel");
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(false);

        mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_unknownAttachment_is404() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/{id}", UUID.randomUUID()).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_nonViewer_andUnknown_areIndistinguishable() throws Exception {
        UUID id = uploadText("secret.txt", "contenu confidentiel");
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(false);

        MvcResult forbiddenDoc = mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt())).andReturn();
        MvcResult unknown = mockMvc.perform(get("/api/v1/attachments/{id}", UUID.randomUUID()).with(jwt()))
                .andReturn();

        assertThat(forbiddenDoc.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus());
        assertThat(forbiddenDoc.getResponse().getContentAsString())
                .doesNotContain("secret.txt")
                .doesNotContain("confidentiel");
    }

    @Test
    void get_deletedAttachment_is404() throws Exception {
        UUID id = uploadText("gone.txt", "bye");
        jdbc.update("UPDATE attachments SET deleted_at = now() WHERE id = ?", id);

        mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isNotFound());
    }

    // ── GET : en-têtes de réponse ──────────────────────────────────────────────────────────

    @Test
    void get_responseHeaders_nosniff_sandbox_private_etagSha256() throws Exception {
        byte[] content = "contenu texte brut".getBytes(StandardCharsets.UTF_8);
        MvcResult up = mockMvc.perform(upload("notes.txt", "text/plain", content).with(jwt()))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(up.getResponse().getContentAsString(), "$.id");
        String sha = sha256Hex(content);

        mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + sha + "\""))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"notes.txt\"")))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, content.length));
    }

    @Test
    void get_image_isInline_withSameSecurityHeaders() throws Exception {
        byte[] png = TestImages.png(4, 4);
        MvcResult up = mockMvc.perform(upload("pic.png", "image/png", png).with(jwt()))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(up.getResponse().getContentAsString(), "$.id");
        String sha = JsonPath.read(up.getResponse().getContentAsString(), "$.sha256");

        mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + sha + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline;")));
    }

    @Test
    void get_range_is206_withContentRange_andSecurityHeaders() throws Exception {
        UUID id = uploadText("digits.txt", "0123456789");

        mockMvc.perform(get("/api/v1/attachments/{id}", id).header(HttpHeaders.RANGE, "bytes=2-5").with(jwt()))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("2345"));
    }

    @Test
    void upload_mp4_isDetectedAndServedInline_withRange() throws Exception {
        byte[] mp4 = minimalMp4();
        MvcResult up = mockMvc.perform(upload("clip.mp4", "application/octet-stream", mp4).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("video/mp4"))
                .andReturn();
        String id = JsonPath.read(up.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/attachments/{id}", id).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline;")));

        mockMvc.perform(get("/api/v1/attachments/{id}", id)
                        .header(HttpHeaders.RANGE, "bytes=0-3")
                        .with(jwt()))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 0-3/" + mp4.length));
    }

    @Test
    void upload_webm_isDetected() throws Exception {
        mockMvc.perform(upload("clip.webm", "application/octet-stream", minimalWebm()).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("video/webm"));
    }

    @Test
    void upload_videoOverDistinctLimit_is413() throws Exception {
        byte[] mp4 = minimalMp4();
        byte[] big = new byte[MAX_MB * 1024 * 1024 + 1];
        System.arraycopy(mp4, 0, big, 0, mp4.length);
        java.util.Arrays.fill(big, mp4.length, big.length, (byte) 0);

        mockMvc.perform(upload("big.mp4", "video/mp4", big).with(jwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAYLOAD_TOO_LARGE));

        assertThat(count("SELECT count(*) FROM attachments")).isZero();
        assertThat(blobFileCount()).isZero();
    }

    @Test
    void upload_filenameIsSanitised_pathAndMarkupStripped() throws Exception {
        mockMvc.perform(upload("../../etc/<evil>.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("evil.txt"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static MockMultipartHttpServletRequestBuilder upload(String filename, String contentType, byte[] bytes) {
        return multipart("/api/v1/documents/{id}/attachments", DOC)
                .file(new MockMultipartFile("file", filename, contentType, bytes));
    }

    private UUID uploadText(String filename, String text) throws Exception {
        MvcResult res = mockMvc.perform(upload(filename, "text/plain", text.getBytes(StandardCharsets.UTF_8))
                        .with(jwt()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(res.getResponse().getContentAsString(), "$.id"));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private byte[] storedBytes(UUID attachmentId) throws Exception {
        UUID key = jdbc.queryForObject(
                "SELECT storage_key FROM attachments WHERE id = ?", UUID.class, attachmentId);
        assertThat(key).isNotNull();
        try (BlobStore.BlobObject obj = blobStore.get(BlobKeys.requireValid(key.toString()), Optional.empty())
                .orElseThrow()) {
            return obj.content().readAllBytes();
        }
    }

    private long blobFileCount() throws IOException {
        try (var files = Files.list(blobDir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** ISO BMFF minimal avec boîte {@code ftyp} — Tika → {@code video/mp4}. */
    private static byte[] minimalMp4() {
        return new byte[] {
                0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x01,
                0x69, 0x73, 0x6F, 0x6D, 0x61, 0x76, 0x63, 0x31
        };
    }

    /** En-tête EBML WebM — Tika → {@code video/webm}. */
    private static byte[] minimalWebm() {
        return new byte[] {
                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x1F, 0x42, (byte) 0x86, (byte) 0x81, 0x01,
                0x42, (byte) 0xF7, (byte) 0x81, 0x01, 0x42, (byte) 0xF2, (byte) 0x81, 0x04,
                0x42, (byte) 0xF3, (byte) 0x81, 0x08, 0x42, (byte) 0x82, (byte) 0x84,
                0x77, 0x65, 0x62, 0x6D
        };
    }
}
