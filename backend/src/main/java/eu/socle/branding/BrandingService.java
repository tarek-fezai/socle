// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import eu.socle.attachment.ImageSanitizer;
import eu.socle.attachment.MediaTypeDetector;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.blob.BlobKeys;
import eu.socle.blob.BlobStore;
import eu.socle.branding.BrandingDtos.BrandingAdminView;
import eu.socle.branding.BrandingDtos.PublicBrandingView;
import eu.socle.branding.BrandingDtos.TestEmailResponse;
import eu.socle.branding.BrandingDtos.UpdateBrandingRequest;
import eu.socle.config.SocleProperties;
import eu.socle.identity.IdentityFacade;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Personnalisation d'instance (accent, logo, favicon, expéditeur) — admin réservé
 * {@code ADMINISTRATEUR_SYSTEME}, audité ; lecture publique minimale via {@link #publicView()}.
 *
 * <p>Instance auto-hébergée : aucune notion de domaine personnalisé / DNS / SaaS ; l'URL publique
 * vient de {@code SOCLE_PUBLIC_BASE_URL} (lecture seule).
 */
@Service
public class BrandingService {

    public static final String PUBLIC_LOGO_PATH = "/api/v1/public/branding/logo";
    public static final String PUBLIC_FAVICON_PATH = "/api/v1/public/branding/favicon";

    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    static final long MAX_FAVICON_BYTES = 1024L * 1024;
    static final int MAX_SENDER_NAME = 100;
    static final int MAX_EMAIL = 254;

    /** PNG / WebP uniquement — SVG (script, XXE) jamais accepté. */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/png", "image/webp");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s<>\"',;]+@[^@\\s<>\"',;]+\\.[^@\\s<>\"',;]+$");

    private final JdbcTemplate jdbc;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;
    private final BlobStore blobStore;
    private final SocleProperties properties;
    private final MediaTypeDetector mediaTypeDetector;
    private final ImageSanitizer imageSanitizer;
    private final BrandingMailSender mailSender;

    @Autowired
    public BrandingService(
            JdbcTemplate jdbc,
            IdentityFacade identityFacade,
            AuditService auditService,
            BlobStore blobStore,
            SocleProperties properties,
            MediaTypeDetector mediaTypeDetector,
            ImageSanitizer imageSanitizer,
            BrandingMailSender mailSender
    ) {
        this.jdbc = jdbc;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
        this.blobStore = blobStore;
        this.properties = properties;
        this.mediaTypeDetector = mediaTypeDetector;
        this.imageSanitizer = imageSanitizer;
        this.mailSender = mailSender;
    }

    // ── Admin ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BrandingAdminView get(Jwt jwt) {
        requireAdmin(jwt);
        return adminView(readRow());
    }

    @Transactional
    public BrandingAdminView update(Jwt jwt, UpdateBrandingRequest request) {
        UserEntity admin = requireAdmin(jwt);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corps requis");
        }
        String accent = normalizeAccent(request.accentColor());
        String senderName = normalizeSenderName(request.senderName());
        String senderEmail = normalizeEmail(request.senderEmail(), "senderEmail");
        boolean hide = Boolean.TRUE.equals(request.hidePoweredBy());
        if (senderName != null && senderEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "senderEmail requis lorsque senderName est renseigné");
        }

        ensureRow();
        Row before = readRow();
        jdbc.update("""
                UPDATE branding_settings
                   SET accent_color = ?, hide_powered_by = ?, sender_name = ?, sender_email = ?,
                       updated_at = now()
                 WHERE id = true
                """, accent, hide, senderName, senderEmail);

        Map<String, Object> changes = new LinkedHashMap<>();
        change(changes, "accentColor", before.accentColor(), accent);
        change(changes, "hidePoweredBy", before.hidePoweredBy(), hide);
        change(changes, "senderName", before.senderName(), senderName);
        change(changes, "senderEmail", before.senderEmail(), senderEmail);
        auditService.record(admin.getId(), false, AuditActions.BRANDING_UPDATED,
                "branding_settings", null, changes, null);
        return adminView(readRow());
    }

    @Transactional
    public BrandingAdminView uploadLogo(Jwt jwt, MultipartFile file) {
        return uploadImage(jwt, file, ImageKind.LOGO);
    }

    @Transactional
    public BrandingAdminView uploadFavicon(Jwt jwt, MultipartFile file) {
        return uploadImage(jwt, file, ImageKind.FAVICON);
    }

    @Transactional
    public BrandingAdminView removeLogo(Jwt jwt) {
        return removeImage(jwt, ImageKind.LOGO);
    }

    @Transactional
    public BrandingAdminView removeFavicon(Jwt jwt) {
        return removeImage(jwt, ImageKind.FAVICON);
    }

    /**
     * Envoie un e-mail de test avec l'expéditeur configuré. {@code to} : défaut = e-mail de
     * l'administrateur connecté.
     */
    @Transactional
    public TestEmailResponse sendTestEmail(Jwt jwt, String toRaw) {
        UserEntity admin = requireAdmin(jwt);
        Row row = readRow();
        if (row.senderEmail() == null) {
            throw ApiErrors.brandingSenderNotConfigured();
        }
        String to = normalizeEmail(toRaw == null || toRaw.isBlank() ? admin.getEmail() : toRaw, "to");
        if (to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destinataire requis");
        }
        String instanceName = instanceName();
        String fromName = row.senderName() != null ? row.senderName() : instanceName;
        mailSender.send(new BrandingMailSender.Message(
                fromName,
                row.senderEmail(),
                to,
                "[" + instanceName + "] E-mail de test",
                "Ceci est un e-mail de test envoyé depuis l'administration de " + instanceName
                        + " avec l'expéditeur configuré (" + fromName + " <" + row.senderEmail() + ">)."));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("to", to);
        meta.put("from", row.senderEmail());
        meta.put("channel", mailSender.channel());
        auditService.record(admin.getId(), false, AuditActions.BRANDING_TEST_EMAIL_SENT,
                "branding_settings", null, meta, null);
        return new TestEmailResponse("sent", to, row.senderEmail(), mailSender.channel());
    }

    // ── Public ──────────────────────────────────────────────────────────────

    /** Seuls champs exposés sans authentification. */
    @Transactional(readOnly = true)
    public PublicBrandingView publicView() {
        Row row = readRow();
        return new PublicBrandingView(
                instanceName(),
                row.accentColor(),
                row.logoKey() != null ? PUBLIC_LOGO_PATH : null,
                row.faviconKey() != null ? PUBLIC_FAVICON_PATH : null,
                row.hidePoweredBy());
    }

    /** Image publique (logo ou favicon) — vide si non configurée. */
    @Transactional(readOnly = true)
    public Optional<PublicImage> openPublicImage(boolean favicon) {
        Row row = readRow();
        String key = favicon ? row.faviconKey() : row.logoKey();
        if (key == null) {
            return Optional.empty();
        }
        Optional<BlobStore.BlobObject> blob = blobStore.get(key, Optional.empty());
        if (blob.isEmpty()) {
            return Optional.empty();
        }
        String mediaType = favicon ? row.faviconMediaType() : row.logoMediaType();
        byte[] bytes;
        try (BlobStore.BlobObject obj = blob.get()) {
            bytes = obj.content().readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Lecture blob branding impossible: " + key, e);
        }
        String etag = "\"" + Long.toHexString(row.updatedAt() == null ? 0 : row.updatedAt().toEpochMilli())
                + "-" + bytes.length + "\"";
        return Optional.of(new PublicImage(bytes, mediaType == null ? "image/png" : mediaType, etag));
    }

    public record PublicImage(byte[] bytes, String mediaType, String etag) {}

    // ── Images ──────────────────────────────────────────────────────────────

    private enum ImageKind {
        LOGO(BlobKeys.BRANDING_LOGO, "logo_blob_key", "logo_media_type", MAX_LOGO_BYTES,
                AuditActions.BRANDING_LOGO_UPLOADED, AuditActions.BRANDING_LOGO_REMOVED),
        FAVICON(BlobKeys.BRANDING_FAVICON, "favicon_blob_key", "favicon_media_type", MAX_FAVICON_BYTES,
                AuditActions.BRANDING_FAVICON_UPLOADED, AuditActions.BRANDING_FAVICON_REMOVED);

        final String blobKey;
        final String keyColumn;
        final String typeColumn;
        final long maxBytes;
        final String uploadedAction;
        final String removedAction;

        ImageKind(String blobKey, String keyColumn, String typeColumn, long maxBytes,
                  String uploadedAction, String removedAction) {
            this.blobKey = blobKey;
            this.keyColumn = keyColumn;
            this.typeColumn = typeColumn;
            this.maxBytes = maxBytes;
            this.uploadedAction = uploadedAction;
            this.removedAction = removedAction;
        }
    }

    private BrandingAdminView uploadImage(Jwt jwt, MultipartFile file, ImageKind kind) {
        UserEntity admin = requireAdmin(jwt);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier requis");
        }
        String declared = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (declared.contains("svg")) {
            throw ApiErrors.brandingImageTypeRejected(declared);
        }
        if (file.getSize() > kind.maxBytes) {
            throw ApiErrors.payloadTooLarge();
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lecture du fichier impossible");
        }
        if (raw.length > kind.maxBytes) {
            throw ApiErrors.payloadTooLarge();
        }

        // Type détecté par octets magiques (jamais Content-Type ni extension) : SVG → refusé.
        String detected;
        try {
            detected = mediaTypeDetector.detect(new ByteArrayInputStream(raw), "branding");
        } catch (IOException e) {
            throw ApiErrors.brandingImageTypeRejected(null);
        }
        if (!ALLOWED_IMAGE_TYPES.contains(detected)) {
            throw ApiErrors.brandingImageTypeRejected(detected);
        }
        byte[] stored = raw;
        String mediaType = detected;
        Integer width;
        Integer height;
        try {
            // Re-encodage (retire EXIF / métadonnées) + plafond de pixels anti-bombe.
            Optional<ImageSanitizer.Result> sanitized = imageSanitizer.sanitizeIfImage(raw, detected);
            if (sanitized.isEmpty()) {
                throw ApiErrors.brandingImageTypeRejected(detected);
            }
            stored = sanitized.get().bytes();
            mediaType = sanitized.get().mediaType();
            width = sanitized.get().width();
            height = sanitized.get().height();
        } catch (CodedStatusException e) {
            throw e;
        } catch (IOException e) {
            throw ApiErrors.brandingImageTypeRejected(detected);
        }
        if (!ALLOWED_IMAGE_TYPES.contains(mediaType)) {
            throw ApiErrors.brandingImageTypeRejected(mediaType);
        }

        blobStore.put(kind.blobKey, new ByteArrayInputStream(stored), stored.length, mediaType);
        ensureRow();
        jdbc.update("UPDATE branding_settings SET " + kind.keyColumn + " = ?, " + kind.typeColumn
                + " = ?, updated_at = now() WHERE id = true", kind.blobKey, mediaType);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mediaType", mediaType);
        meta.put("sizeBytes", stored.length);
        meta.put("width", width);
        meta.put("height", height);
        auditService.record(admin.getId(), false, kind.uploadedAction, "branding_settings", null, meta, null);
        return adminView(readRow());
    }

    private BrandingAdminView removeImage(Jwt jwt, ImageKind kind) {
        UserEntity admin = requireAdmin(jwt);
        ensureRow();
        Row row = readRow();
        String key = kind == ImageKind.LOGO ? row.logoKey() : row.faviconKey();
        if (key != null) {
            blobStore.delete(key);
            jdbc.update("UPDATE branding_settings SET " + kind.keyColumn + " = NULL, " + kind.typeColumn
                    + " = NULL, updated_at = now() WHERE id = true");
            auditService.record(admin.getId(), false, kind.removedAction,
                    "branding_settings", null, Map.of(), null);
        }
        return adminView(readRow());
    }

    // ── Validation ──────────────────────────────────────────────────────────

    static String normalizeAccent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String color = raw.trim();
        if (!ContrastChecker.isValidHex(color)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "accentColor invalide (format #RRGGBB attendu)");
        }
        color = ContrastChecker.normalizeHex(color);
        double ratio = ContrastChecker.contrastOnWhite(color);
        if (ratio < ContrastChecker.AA_NORMAL_TEXT) {
            throw ApiErrors.accentContrastInsufficient(color, ratio);
        }
        return color;
    }

    private static String normalizeSenderName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim();
        if (name.length() > MAX_SENDER_NAME || hasControlChars(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "senderName invalide (maximum " + MAX_SENDER_NAME + " caractères, sans retour à la ligne)");
        }
        return name;
    }

    private static String normalizeEmail(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String email = raw.trim();
        if (email.length() > MAX_EMAIL || hasControlChars(email) || !EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " : adresse e-mail invalide");
        }
        return email;
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void change(Map<String, Object> changes, String key, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("from", from);
            c.put("to", to);
            changes.put(key, c);
        }
    }

    // ── Persistance ─────────────────────────────────────────────────────────

    private record Row(
            String accentColor,
            boolean hidePoweredBy,
            String senderName,
            String senderEmail,
            String logoKey,
            String logoMediaType,
            String faviconKey,
            String faviconMediaType,
            Instant updatedAt
    ) {}

    private void ensureRow() {
        jdbc.update("INSERT INTO branding_settings (id) VALUES (true) ON CONFLICT (id) DO NOTHING");
    }

    private Row readRow() {
        List<Row> rows = jdbc.query("""
                SELECT accent_color, hide_powered_by, sender_name, sender_email,
                       logo_blob_key, logo_media_type, favicon_blob_key, favicon_media_type, updated_at
                  FROM branding_settings WHERE id = true
                """,
                (rs, i) -> {
                    Timestamp updated = rs.getTimestamp("updated_at");
                    return new Row(
                            rs.getString("accent_color"),
                            rs.getBoolean("hide_powered_by"),
                            rs.getString("sender_name"),
                            rs.getString("sender_email"),
                            rs.getString("logo_blob_key"),
                            rs.getString("logo_media_type"),
                            rs.getString("favicon_blob_key"),
                            rs.getString("favicon_media_type"),
                            updated == null ? null : updated.toInstant());
                });
        return rows.isEmpty()
                ? new Row(null, false, null, null, null, null, null, null, null)
                : rows.getFirst();
    }

    private BrandingAdminView adminView(Row row) {
        SocleProperties.Instance instance = properties == null ? null : properties.instance();
        String base = instance == null ? null : instance.effectivePublicBaseUrl();
        return new BrandingAdminView(
                instanceName(),
                row.accentColor(),
                row.hidePoweredBy(),
                row.senderName(),
                row.senderEmail(),
                row.logoKey() != null,
                row.faviconKey() != null,
                row.logoKey() != null ? PUBLIC_LOGO_PATH : null,
                row.faviconKey() != null ? PUBLIC_FAVICON_PATH : null,
                base,
                "Défini par la variable d'environnement SOCLE_PUBLIC_BASE_URL (non modifiable depuis l'interface).",
                row.updatedAt());
    }

    private String instanceName() {
        SocleProperties.Instance instance = properties == null ? null : properties.instance();
        return instance == null ? "Socle" : instance.effectiveDisplayName();
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }
}
