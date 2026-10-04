// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import eu.socle.attachment.AttachmentDtos.AttachmentResponse;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.blob.BlobKeys;
import eu.socle.blob.BlobStore;
import eu.socle.user.UserSyncService;
import eu.socle.web.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pièces jointes : upload (éditeur), lecture via backend (viewer), purge document/orphelins.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final Set<String> INLINE_IMAGES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");

    private final JdbcTemplate jdbc;
    private final BlobStore blobStore;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AttachmentProperties properties;
    private final MediaTypeDetector mediaTypeDetector;
    private final ImageSanitizer imageSanitizer;
    private final Clock clock;

    @Autowired
    public AttachmentService(
            JdbcTemplate jdbc,
            BlobStore blobStore,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            AttachmentProperties properties,
            MediaTypeDetector mediaTypeDetector,
            ImageSanitizer imageSanitizer
    ) {
        this(jdbc, blobStore, userSyncService, authorizationService, auditService,
                properties, mediaTypeDetector, imageSanitizer, Clock.systemUTC());
    }

    AttachmentService(
            JdbcTemplate jdbc,
            BlobStore blobStore,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            AttachmentProperties properties,
            MediaTypeDetector mediaTypeDetector,
            ImageSanitizer imageSanitizer,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.properties = properties;
        this.mediaTypeDetector = mediaTypeDetector;
        this.imageSanitizer = imageSanitizer;
        this.clock = clock;
    }

    @Transactional
    public AttachmentResponse upload(Jwt jwt, UUID documentId, MultipartFile file) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        requireActiveDocument(documentId);
        assertNoApprovalInProgress(documentId);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier requis");
        }
        long declared = file.getSize();
        if (declared > properties.maxBytes()) {
            throw ApiErrors.payloadTooLarge();
        }

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lecture du fichier impossible");
        }
        if (raw.length > properties.maxBytes()) {
            throw ApiErrors.payloadTooLarge();
        }

        String filenameHint = sanitizeFilename(file.getOriginalFilename());
        String detected;
        try {
            detected = mediaTypeDetector.detect(new ByteArrayInputStream(raw), filenameHint);
        } catch (IOException e) {
            throw ApiErrors.attachmentTypeRejected(null);
        }
        if (!properties.isAllowed(detected)) {
            throw ApiErrors.attachmentTypeRejected(detected);
        }

        String mediaType = detected;
        Integer width = null;
        Integer height = null;
        byte[] stored = raw;
        try {
            Optional<ImageSanitizer.Result> sanitized = imageSanitizer.sanitizeIfImage(raw, detected);
            if (sanitized.isPresent()) {
                ImageSanitizer.Result r = sanitized.get();
                stored = r.bytes();
                mediaType = r.mediaType();
                width = r.width();
                height = r.height();
                if (!properties.isAllowed(mediaType)) {
                    throw ApiErrors.attachmentTypeRejected(mediaType);
                }
            }
        } catch (IOException e) {
            throw ApiErrors.attachmentTypeRejected(detected);
        }

        String storageKey = BlobKeys.newKey();
        String sha256 = sha256Hex(stored);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();

        blobStore.put(storageKey, new ByteArrayInputStream(stored), stored.length, mediaType);

        try {
            jdbc.update("""
                    INSERT INTO attachments (
                        id, document_id, uploaded_by, original_filename, media_type,
                        size_bytes, sha256, storage_key, width, height, created_at, deleted_at, referenced_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, NULL, NULL)
                    """,
                    id,
                    documentId,
                    user.getId(),
                    filenameHint,
                    mediaType,
                    (long) stored.length,
                    sha256,
                    UUID.fromString(storageKey),
                    width,
                    height,
                    Timestamp.from(now));
        } catch (RuntimeException e) {
            try {
                blobStore.delete(storageKey);
            } catch (RuntimeException cleanup) {
                log.warn("Rollback blob après échec INSERT: {}", storageKey, cleanup);
            }
            throw e;
        }

        auditService.record(
                user.getId(),
                false,
                AuditActions.ATTACHMENT_UPLOADED,
                "attachment",
                id,
                Map.of(
                        "documentId", documentId.toString(),
                        "mediaType", mediaType,
                        "sizeBytes", stored.length,
                        "filename", filenameHint
                ),
                null
        );

        return new AttachmentResponse(
                id, documentId, filenameHint, mediaType, stored.length, sha256, width, height, now);
    }

    /**
     * Métadonnées + flux pour GET — 404 si absent ou non-viewer du document propriétaire
     * (pas de 403 pour ne pas révéler l'existence).
     */
    @Transactional(readOnly = true)
    public AttachmentContent openForDownload(Jwt jwt, UUID attachmentId, Optional<BlobStore.ByteRange> range) {
        var user = userSyncService.syncFromJwt(jwt);
        AttachmentRow row = findActive(attachmentId)
                .orElseThrow(() -> notFound());
        if (!authorizationService.hasRelation(user.getId(), "document", row.documentId(), "viewer")) {
            throw notFound();
        }
        Optional<BlobStore.BlobObject> blob = blobStore.get(row.storageKey().toString(), range);
        if (blob.isEmpty()) {
            throw notFound();
        }
        return new AttachmentContent(row, blob.get());
    }

    /** Marque comme référencées les pièces jointes citées dans un corps TipTap (sticky). */
    @Transactional
    public void markReferenced(UUID documentId, Map<String, Object> body) {
        Set<UUID> ids = extractAttachmentIds(body);
        if (ids.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (UUID id : ids) {
            jdbc.update("""
                    UPDATE attachments
                       SET referenced_at = ?
                     WHERE id = ?
                       AND document_id = ?
                       AND deleted_at IS NULL
                       AND referenced_at IS NULL
                    """,
                    Timestamp.from(now), id, documentId);
        }
    }

    /** Purge physique de toutes les pièces d'un document (corbeille définitive) — idempotent. */
    @Transactional
    public int purgeForDocument(UUID documentId, UUID actorId, boolean actorIsSystem) {
        List<AttachmentRow> rows = jdbc.query("""
                SELECT id, document_id, uploaded_by, original_filename, media_type, size_bytes,
                       sha256, storage_key, width, height, created_at, deleted_at, referenced_at
                  FROM attachments
                 WHERE document_id = ?
                """,
                (rs, i) -> mapRow(rs),
                documentId);
        int n = 0;
        for (AttachmentRow row : rows) {
            purgeOne(row, actorId, actorIsSystem, "document_purge");
            n++;
        }
        return n;
    }

    /** Orphelines (jamais référencées) plus anciennes que N jours — idempotent, auditée. */
    @Transactional
    public int purgeOrphans() {
        Instant cutoff = clock.instant().minusSeconds(Math.max(1, properties.getOrphanRetentionDays()) * 86_400L);
        List<AttachmentRow> rows = jdbc.query("""
                SELECT id, document_id, uploaded_by, original_filename, media_type, size_bytes,
                       sha256, storage_key, width, height, created_at, deleted_at, referenced_at
                  FROM attachments
                 WHERE deleted_at IS NULL
                   AND referenced_at IS NULL
                   AND created_at < ?
                """,
                (rs, i) -> mapRow(rs),
                Timestamp.from(cutoff));
        int n = 0;
        for (AttachmentRow row : rows) {
            purgeOne(row, null, true, "orphan");
            n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportPersonalMetadata(UUID userId) {
        return jdbc.query("""
                SELECT id, document_id, original_filename, media_type, size_bytes, created_at, deleted_at
                  FROM attachments
                 WHERE uploaded_by = ?
                 ORDER BY created_at ASC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("documentId", rs.getObject("document_id", UUID.class).toString());
                    m.put("filename", rs.getString("original_filename"));
                    m.put("mediaType", rs.getString("media_type"));
                    m.put("sizeBytes", rs.getLong("size_bytes"));
                    Timestamp created = rs.getTimestamp("created_at");
                    m.put("createdAt", created == null ? null : created.toInstant().toString());
                    Timestamp deleted = rs.getTimestamp("deleted_at");
                    m.put("deletedAt", deleted == null ? null : deleted.toInstant().toString());
                    return m;
                },
                userId);
    }

    public static boolean isInlineImage(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String base = mediaType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return INLINE_IMAGES.contains(base);
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> extractAttachmentIds(Map<String, Object> body) {
        Set<UUID> ids = new HashSet<>();
        if (body == null) {
            return ids;
        }
        collectIds(body, ids);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static void collectIds(Object node, Set<UUID> ids) {
        if (!(node instanceof Map<?, ?> m)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) m;
        String type = String.valueOf(map.getOrDefault("type", ""));
        if ("image".equals(type) || "attachment".equals(type)) {
            Object attrs = map.get("attrs");
            if (attrs instanceof Map<?, ?> am) {
                Object id = am.get("id");
                if (id != null) {
                    try {
                        ids.add(UUID.fromString(String.valueOf(id)));
                    } catch (IllegalArgumentException ignored) {
                        // ignore
                    }
                }
            }
        }
        Object content = map.get("content");
        if (content instanceof List<?> list) {
            for (Object child : list) {
                collectIds(child, ids);
            }
        }
    }

    private void purgeOne(AttachmentRow row, UUID actorId, boolean actorIsSystem, String reason) {
        String key = row.storageKey().toString();
        try {
            blobStore.delete(key);
        } catch (RuntimeException e) {
            log.warn("Suppression blob {} échouée (suite purge DB): {}", key, e.toString());
        }
        jdbc.update("DELETE FROM attachments WHERE id = ?", row.id());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentId", row.documentId().toString());
        meta.put("storageKey", key);
        meta.put("reason", reason);
        meta.put("filename", row.originalFilename());
        meta.put("mediaType", row.mediaType());
        meta.put("sizeBytes", row.sizeBytes());
        auditService.record(
                actorId,
                actorIsSystem,
                AuditActions.ATTACHMENT_PURGED,
                "attachment",
                row.id(),
                meta,
                null
        );
    }

    private Optional<AttachmentRow> findActive(UUID id) {
        List<AttachmentRow> rows = jdbc.query("""
                SELECT id, document_id, uploaded_by, original_filename, media_type, size_bytes,
                       sha256, storage_key, width, height, created_at, deleted_at, referenced_at
                  FROM attachments
                 WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> mapRow(rs),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private AttachmentRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp deleted = rs.getTimestamp("deleted_at");
        Timestamp referenced = rs.getTimestamp("referenced_at");
        return new AttachmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class),
                rs.getString("original_filename"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("storage_key", UUID.class),
                (Integer) rs.getObject("width"),
                (Integer) rs.getObject("height"),
                created == null ? null : created.toInstant(),
                deleted == null ? null : deleted.toInstant(),
                referenced == null ? null : referenced.toInstant()
        );
    }

    private void requireActiveDocument(UUID documentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ? AND deleted_at IS NULL",
                Integer.class,
                documentId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
    }

    private void assertNoApprovalInProgress(UUID documentId) {
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE document_id = ? AND status = 'en_cours'",
                Integer.class,
                documentId);
        if (pending != null && pending > 0) {
            throw ApiErrors.approvalInProgress();
        }
    }

    static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "fichier";
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 200));
        for (int i = 0; i < name.length() && sb.length() < 200; i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`') {
                continue;
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? "fichier" : cleaned;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Pièce jointe introuvable");
    }

    public record AttachmentRow(
            UUID id,
            UUID documentId,
            UUID uploadedBy,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            UUID storageKey,
            Integer width,
            Integer height,
            Instant createdAt,
            Instant deletedAt,
            Instant referencedAt
    ) {}

    public record AttachmentContent(AttachmentRow row, BlobStore.BlobObject blob) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            blob.close();
        }

        public InputStream content() {
            return blob.content();
        }
    }

    /** Utilitaire tests / export : liste des ids dans un corps. */
    public static List<UUID> attachmentIdsIn(Map<String, Object> body) {
        return new ArrayList<>(extractAttachmentIds(body));
    }

    static String contentDisposition(AttachmentRow row) {
        String filename = row.originalFilename() == null ? "fichier" : row.originalFilename();
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = isInlineImage(row.mediaType()) ? "inline" : "attachment";
        return disposition + "; filename=\"" + filename.replace("\"", "")
                + "\"; filename*=UTF-8''" + encoded;
    }
}
