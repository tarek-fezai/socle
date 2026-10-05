// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.attachment.AttachmentProperties;
import eu.socle.attachment.ImageSanitizer;
import eu.socle.attachment.MediaTypeDetector;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.blob.BlobKeys;
import eu.socle.blob.LocalBlobStore;
import eu.socle.branding.BrandingDtos.BrandingAdminView;
import eu.socle.branding.BrandingDtos.PublicBrandingView;
import eu.socle.branding.BrandingDtos.TestEmailResponse;
import eu.socle.branding.BrandingDtos.UpdateBrandingRequest;
import eu.socle.config.SocleProperties;
import eu.socle.identity.IdentityFacade;
import eu.socle.testsupport.MigratedPostgres;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrandingServiceDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /** WebP 1×1 sans perte (lecteur imageio-webp requis pour le ré-encodage). */
    static final byte[] TINY_WEBP = Base64.getDecoder()
            .decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

    @TempDir Path blobDir;
    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    LocalBlobStore blobs;
    CapturingMailSender mail;
    BrandingService service;
    Jwt jwt;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'admin@example.com', 'Admin')", ADMIN);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM branding_settings");
        jdbc.update("INSERT INTO branding_settings (id) VALUES (true)");
        blobs = new LocalBlobStore(blobDir);
        mail = new CapturingMailSender();

        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        admin.setEmail("admin@example.com");
        admin.setDisplayName("Admin");
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(ADMIN.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        SocleProperties props = new SocleProperties(null, null, null,
                new SocleProperties.Instance("Acme Docs", null, "https://docs.acme.example/"), null);
        service = new BrandingService(jdbc, identityFacade, auditService, blobs, props,
                new MediaTypeDetector(), new ImageSanitizer(new AttachmentProperties()), mail);
    }

    // ── Accent / champs ─────────────────────────────────────────────────────

    @Test
    void update_savesFieldsAndAudits() {
        BrandingAdminView v = service.update(jwt,
                new UpdateBrandingRequest("#1d4ed8", true, "Acme Docs", "no-reply@acme.example"));

        assertThat(v.accentColor()).isEqualTo("#1D4ED8");
        assertThat(v.hidePoweredBy()).isTrue();
        assertThat(v.senderName()).isEqualTo("Acme Docs");
        assertThat(v.senderEmail()).isEqualTo("no-reply@acme.example");
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.BRANDING_UPDATED),
                eq("branding_settings"), isNull(), anyMap(), isNull());
    }

    @Test
    void update_rejectsAccentFailingWcagAaOnWhite() {
        for (String bad : new String[] {"#FFFFFF", "#FFEB3B", "#777777"}) {
            assertThatThrownBy(() -> service.update(jwt, new UpdateBrandingRequest(bad, false, null, null)))
                    .isInstanceOf(CodedStatusException.class)
                    .extracting(e -> ((CodedStatusException) e).getCode())
                    .isEqualTo(ApiErrors.ACCENT_CONTRAST_INSUFFICIENT);
        }
        assertThat(service.get(jwt).accentColor()).isNull();
    }

    @Test
    void update_rejectsMalformedValues() {
        assertThatThrownBy(() -> service.update(jwt, new UpdateBrandingRequest("blue", false, null, null)))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.update(jwt,
                new UpdateBrandingRequest(null, false, null, "not-an-email"))).hasMessageContaining("400");
        assertThatThrownBy(() -> service.update(jwt,
                new UpdateBrandingRequest(null, false, "x\r\nBcc: evil@x.io", "a@b.example")))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.update(jwt,
                new UpdateBrandingRequest(null, false, "Name", null))).hasMessageContaining("400");
    }

    @Test
    void update_requiresSystemAdmin() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> service.update(jwt, new UpdateBrandingRequest(null, false, null, null)))
                .hasMessageContaining("403");
        assertThatThrownBy(() -> service.get(jwt)).hasMessageContaining("403");
    }

    @Test
    void adminView_exposesReadOnlyPublicBaseUrl_withoutDnsConcepts() throws Exception {
        BrandingAdminView v = service.get(jwt);
        assertThat(v.publicBaseUrl()).isEqualTo("https://docs.acme.example");
        assertThat(v.publicBaseUrlNote()).contains("SOCLE_PUBLIC_BASE_URL");
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(v);
        assertThat(fieldNames(json)).noneMatch(n -> n.toLowerCase().contains("domain")
                || n.toLowerCase().contains("dns") || n.toLowerCase().contains("spf"));
    }

    // ── Images ──────────────────────────────────────────────────────────────

    @Test
    void uploadLogo_png_storesUnderReservedKey_andIsServedPublicly() throws Exception {
        BrandingAdminView v = service.uploadLogo(jwt, file("logo.png", "image/png", png(64, 32)));

        assertThat(v.hasLogo()).isTrue();
        assertThat(v.logoUrl()).isEqualTo("/api/v1/public/branding/logo");
        assertThat(blobs.exists(BlobKeys.BRANDING_LOGO)).isTrue();
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.BRANDING_LOGO_UPLOADED),
                eq("branding_settings"), isNull(), anyMap(), isNull());

        var image = service.openPublicImage(false).orElseThrow();
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(image.bytes())).getWidth()).isEqualTo(64);
        assertThat(image.etag()).isNotBlank();
        assertThat(service.openPublicImage(true)).isEmpty();
    }

    @Test
    void uploadFavicon_webp_isAcceptedAndReEncodedToPng() {
        BrandingAdminView v = service.uploadFavicon(jwt, file("fav.webp", "image/webp", TINY_WEBP));
        assertThat(v.hasFavicon()).isTrue();
        assertThat(blobs.exists(BlobKeys.BRANDING_FAVICON)).isTrue();
        assertThat(service.openPublicImage(true).orElseThrow().mediaType()).isEqualTo("image/png");
    }

    @Test
    void upload_svgRejected_byDeclaredType() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.uploadLogo(jwt, file("logo.svg", "image/svg+xml", svg)))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.BRANDING_IMAGE_TYPE_REJECTED);
        assertThat(blobs.exists(BlobKeys.BRANDING_LOGO)).isFalse();
    }

    @Test
    void upload_svgDisguisedAsPng_rejectedBySniffing() {
        byte[] svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.uploadFavicon(jwt, file("fav.png", "image/png", svg)))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.BRANDING_IMAGE_TYPE_REJECTED);
        assertThat(blobs.exists(BlobKeys.BRANDING_FAVICON)).isFalse();
    }

    @Test
    void upload_jpegAndGifRejected_onlyPngWebp() throws Exception {
        assertThatThrownBy(() -> service.uploadLogo(jwt, file("l.jpg", "image/jpeg", image("jpg", 8, 8))))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.BRANDING_IMAGE_TYPE_REJECTED);
    }

    @Test
    void upload_tooLarge_isRejected() {
        byte[] big = new byte[(int) BrandingService.MAX_FAVICON_BYTES + 1];
        assertThatThrownBy(() -> service.uploadFavicon(jwt, file("f.png", "image/png", big)))
                .isInstanceOf(CodedStatusException.class);
    }

    @Test
    void removeLogo_deletesBlobAndClearsReference() throws Exception {
        service.uploadLogo(jwt, file("logo.png", "image/png", png(8, 8)));
        BrandingAdminView v = service.removeLogo(jwt);
        assertThat(v.hasLogo()).isFalse();
        assertThat(blobs.exists(BlobKeys.BRANDING_LOGO)).isFalse();
        assertThat(service.openPublicImage(false)).isEmpty();
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.BRANDING_LOGO_REMOVED),
                eq("branding_settings"), isNull(), anyMap(), isNull());
    }

    // ── Public ──────────────────────────────────────────────────────────────

    @Test
    void publicView_hasOnlyTheFiveAllowedFields_noSenderOrAdminData() throws Exception {
        service.update(jwt, new UpdateBrandingRequest("#1D4ED8", true, "Acme", "secret-sender@acme.example"));
        service.uploadLogo(jwt, file("logo.png", "image/png", png(8, 8)));

        PublicBrandingView pub = service.publicView();
        JsonNode json = new ObjectMapper().valueToTree(pub);

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "instanceName", "accentColor", "logoUrl", "faviconUrl", "hidePoweredBy");
        assertThat(pub.instanceName()).isEqualTo("Acme Docs");
        assertThat(pub.accentColor()).isEqualTo("#1D4ED8");
        assertThat(pub.hidePoweredBy()).isTrue();
        assertThat(pub.logoUrl()).isEqualTo("/api/v1/public/branding/logo");
        assertThat(pub.faviconUrl()).isNull();
        assertThat(json.toString()).doesNotContain("secret-sender").doesNotContain("@");
    }

    // ── E-mail de test ──────────────────────────────────────────────────────

    @Test
    void testEmail_usesConfiguredSender_andDefaultsToAdminAddress() {
        service.update(jwt, new UpdateBrandingRequest(null, false, "Acme Docs", "no-reply@acme.example"));

        TestEmailResponse r = service.sendTestEmail(jwt, null);

        assertThat(r.status()).isEqualTo("sent");
        assertThat(r.to()).isEqualTo("admin@example.com");
        assertThat(mail.sent).hasSize(1);
        BrandingMailSender.Message m = mail.sent.getFirst();
        assertThat(m.fromEmail()).isEqualTo("no-reply@acme.example");
        assertThat(m.fromName()).isEqualTo("Acme Docs");
        assertThat(m.to()).isEqualTo("admin@example.com");
        assertThat(m.subject()).contains("Acme Docs");
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.BRANDING_TEST_EMAIL_SENT),
                eq("branding_settings"), isNull(), anyMap(), isNull());
    }

    @Test
    void testEmail_explicitRecipient() {
        service.update(jwt, new UpdateBrandingRequest(null, false, null, "no-reply@acme.example"));
        service.sendTestEmail(jwt, "someone@example.org");
        assertThat(mail.sent.getFirst().to()).isEqualTo("someone@example.org");
        assertThat(mail.sent.getFirst().fromName()).isEqualTo("Acme Docs"); // repli : nom d'instance
    }

    @Test
    void testEmail_withoutSender_conflict() {
        assertThatThrownBy(() -> service.sendTestEmail(jwt, null))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.BRANDING_SENDER_NOT_CONFIGURED);
        assertThat(mail.sent).isEmpty();
    }

    // ── Aides ───────────────────────────────────────────────────────────────

    static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    static byte[] png(int w, int h) throws Exception {
        return image("png", w, h);
    }

    static byte[] image(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false)
                .collect(java.util.stream.Collectors.toSet());
    }

    static final class CapturingMailSender implements BrandingMailSender {
        final List<Message> sent = new ArrayList<>();

        @Override
        public void send(Message message) {
            sent.add(message);
        }

        @Override
        public String channel() {
            return "test";
        }
    }
}
