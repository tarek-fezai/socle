// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import eu.socle.attachment.AttachmentService;
import eu.socle.attachment.MediaTypeDetector;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRepository;
import eu.socle.linkpreview.LinkPreviewDtos.PreviewView;
import eu.socle.user.UserSyncService;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aperçu de lien : désactivé par défaut (carte locale URL+domaine, aucun appel externe).
 * Activé : whitelist + anti-SSRF (DnsResolver validant, IP privées, redirections, ports 80/443).
 * Client Apache HttpClient 5 unique pour page et vignette (TLS hostname vérifié).
 */
@Service
public class LinkPreviewService {

    private static final int MAX_REDIRECTS = 3;
    static final int MAX_BYTES = 1_048_576;
    private static final Timeout TIMEOUT = Timeout.ofSeconds(5);

    private final LinkPreviewProperties properties;
    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final ValidatingDnsResolver dnsResolver;
    private final CloseableHttpClient httpClient;
    private final boolean proxyConfigured;
    private AttachmentService attachmentService;
    private MediaTypeDetector mediaTypeDetector;

    /** Compteur simple par utilisateur (fenêtre 1 minute). */
    private final ConcurrentHashMap<UUID, RateWindow> rate = new ConcurrentHashMap<>();

    @Autowired
    public LinkPreviewService(
            LinkPreviewProperties properties,
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            AuditService auditService,
            Clock clock
    ) {
        this(properties, jdbc, userSyncService, authorizationService, documentRepository, auditService, clock,
                new ValidatingDnsResolver());
    }

    /** Constructeur test : DnsResolver injecté (rebinding, etc.). */
    LinkPreviewService(
            LinkPreviewProperties properties,
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            AuditService auditService,
            Clock clock,
            ValidatingDnsResolver dnsResolver
    ) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.dnsResolver = dnsResolver;

        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .build();

        RequestConfig.Builder reqCfg = RequestConfig.custom()
                .setConnectionRequestTimeout(TIMEOUT)
                .setResponseTimeout(TIMEOUT)
                .setRedirectsEnabled(false);

        var clientBuilder = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(reqCfg.build())
                .disableRedirectHandling();

        String proxyUrl = properties.getProxyUrl();
        this.proxyConfigured = proxyUrl != null && !proxyUrl.isBlank();
        if (proxyConfigured) {
            URI proxy = URI.create(proxyUrl.trim());
            int port = proxy.getPort() > 0 ? proxy.getPort() : 8080;
            clientBuilder.setProxy(new HttpHost(proxy.getScheme(), proxy.getHost(), port));
        }

        this.httpClient = clientBuilder.build();
    }

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Autowired(required = false)
    void setMediaTypeDetector(MediaTypeDetector mediaTypeDetector) {
        this.mediaTypeDetector = mediaTypeDetector;
    }

    /** Carte sans fetch (toujours disponible). */
    public PreviewView localCard(String url) {
        URI uri = SsrfGuard.requireHttpUrl(url);
        return new PreviewView(uri.toString(), uri.getHost().toLowerCase(Locale.ROOT), null, null, false);
    }

    /**
     * Fetch administrateur / éditeur quand le feature est activé.
     * {@code documentId} optionnel : si fourni, la vignette est rattachée comme pièce jointe du document.
     */
    @Transactional
    public PreviewView fetch(Jwt jwt, String url, UUID documentId) {
        if (!properties.isEnabled()) {
            return localCard(url);
        }
        var user = userSyncService.syncFromJwt(jwt);
        enforceRateLimit(user.getId());

        URI start = SsrfGuard.requireHttpUrl(url);
        SsrfGuard.requireAllowedDomain(start, properties.allowedDomainSet());
        // Sans proxy : résolution locale validée. Avec proxy : la résolution cible est côté proxy
        // (le proxy DOIT bloquer les plages internes — voir configuration.md).
        if (!proxyConfigured) {
            dnsResolver.resolveOrReject(start.getHost());
        }

        if (documentId != null) {
            documentRepository.findActiveById(documentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
            authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        }

        String norm = normalize(start);
        String hash = sha256(norm);
        Optional<PreviewView> cached = readCache(hash, jwt, documentId);
        if (cached.isPresent()) {
            return cached.get();
        }

        FetchResult fetched = fetchHtml(start);
        String title = extractTitle(fetched.html(), start.getHost());
        UUID thumbId = null;
        if (documentId != null && attachmentService != null && fetched.ogImage() != null) {
            thumbId = tryStoreThumbnail(jwt, documentId, fetched.ogImage());
        }

        Instant now = clock.instant();
        Instant expires = now.plus(Duration.ofHours(Math.max(1, properties.getCacheTtlHours())));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO link_preview_cache
                  (id, url_norm, url_hash, title, domain, thumbnail_attachment_id, fetched_at, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (url_hash) DO UPDATE
                  SET title = excluded.title,
                      domain = excluded.domain,
                      thumbnail_attachment_id = COALESCE(excluded.thumbnail_attachment_id, link_preview_cache.thumbnail_attachment_id),
                      fetched_at = excluded.fetched_at,
                      expires_at = excluded.expires_at
                """,
                id, norm, hash, title, start.getHost().toLowerCase(Locale.ROOT), thumbId,
                Timestamp.from(now), Timestamp.from(expires), user.getId());

        auditService.record(
                user.getId(), false, AuditActions.LINK_PREVIEW_FETCHED,
                documentId != null ? "document" : "user",
                documentId != null ? documentId : user.getId(),
                Map.of("domain", start.getHost(), "urlHash", hash),
                null);

        return new PreviewView(norm, start.getHost().toLowerCase(Locale.ROOT), title, thumbId, true);
    }

    private Optional<PreviewView> readCache(String hash, Jwt jwt, UUID documentId) {
        Instant now = clock.instant();
        List<PreviewView> rows = jdbc.query("""
                SELECT url_norm, domain, title, thumbnail_attachment_id, expires_at
                  FROM link_preview_cache WHERE url_hash = ?
                """,
                (rs, i) -> {
                    Instant exp = rs.getTimestamp("expires_at").toInstant();
                    if (exp.isBefore(now)) {
                        return null;
                    }
                    UUID thumb = (UUID) rs.getObject("thumbnail_attachment_id");
                    if (thumb != null && documentId != null) {
                        thumb = ensureThumbnailForDocument(jwt, documentId, thumb);
                    } else if (thumb != null && documentId == null) {
                        // Pas de document cible : ne pas exposer une vignette d'un autre doc.
                        thumb = null;
                    }
                    return new PreviewView(
                            rs.getString("url_norm"),
                            rs.getString("domain"),
                            rs.getString("title"),
                            thumb,
                            true);
                },
                hash);
        return rows.stream().filter(v -> v != null).findFirst();
    }

    /**
     * Si la vignette appartient déjà au document, la réutilise ; sinon la recopie
     * comme nouvelle pièce jointe du document courant (pas de partage cross-document).
     */
    private UUID ensureThumbnailForDocument(Jwt jwt, UUID documentId, UUID thumbId) {
        List<UUID> owners = jdbc.query(
                "SELECT document_id FROM attachments WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> (UUID) rs.getObject(1),
                thumbId);
        if (owners.isEmpty()) {
            return null;
        }
        if (documentId.equals(owners.getFirst())) {
            return thumbId;
        }
        if (attachmentService == null) {
            return null;
        }
        try (AttachmentService.AttachmentContent content =
                     attachmentService.openForDownload(jwt, thumbId, Optional.empty())) {
            byte[] bytes;
            try (InputStream in = content.blob().content()) {
                bytes = readLimited(in, MAX_BYTES);
            }
            String filename = content.row().originalFilename() != null
                    ? content.row().originalFilename() : "preview.bin";
            // Type détecté côté upload (Tika), pas l'en-tête distant.
            MultipartFile file = new BytesMultipartFile(filename, "application/octet-stream", bytes);
            return attachmentService.upload(jwt, documentId, file).id();
        } catch (Exception e) {
            return null;
        }
    }

    FetchResult fetchHtml(URI start) {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            SsrfGuard.requireHttpUrl(current.toString());
            SsrfGuard.requireAllowedDomain(current, properties.allowedDomainSet());
            if (!proxyConfigured) {
                dnsResolver.resolveOrReject(current.getHost());
            }

            final URI hopUri = current;
            final int hopIdx = hop;
            HttpGet get = new HttpGet(hopUri);
            get.setHeader("User-Agent", "SocleLinkPreview/1.0");
            get.setHeader("Accept", "text/html");

            try {
                return httpClient.execute(get, resp -> {
                    int code = resp.getCode();
                    if (code >= 300 && code < 400) {
                        var locHeader = resp.getFirstHeader("Location");
                        String loc = locHeader != null ? locHeader.getValue() : null;
                        if (loc == null || hopIdx == MAX_REDIRECTS) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Aperçu de lien refusé : redirection invalide");
                        }
                        throw new RedirectSignal(hopUri.resolve(loc));
                    }
                    if (code < 200 || code >= 300) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Aperçu de lien : HTTP " + code);
                    }
                    HttpEntity entity = resp.getEntity();
                    String ctype = entity != null && entity.getContentType() != null
                            ? entity.getContentType() : "";
                    if (!ctype.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                        EntityUtils.consumeQuietly(entity);
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Aperçu de lien refusé : text/html uniquement");
                    }
                    byte[] body;
                    try (InputStream in = entity.getContent()) {
                        body = readLimited(in, MAX_BYTES);
                    }
                    String html = new String(body, StandardCharsets.UTF_8);
                    String ogImage = null;
                    try {
                        Document doc = Jsoup.parse(html, hopUri.toString());
                        var meta = doc.selectFirst("meta[property=og:image]");
                        if (meta != null) {
                            ogImage = meta.attr("abs:content");
                            if (ogImage.isBlank()) {
                                ogImage = null;
                            }
                        }
                    } catch (Exception ignored) {
                        // titre via extractTitle
                    }
                    return new FetchResult(html, ogImage);
                });
            } catch (RedirectSignal rs) {
                current = rs.target();
                continue;
            } catch (ResponseStatusException e) {
                throw e;
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Échec de récupération de l'aperçu");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : trop de redirections");
    }

    private UUID tryStoreThumbnail(Jwt jwt, UUID documentId, String imageUrl) {
        try {
            URI img = SsrfGuard.requireHttpUrl(imageUrl);
            if (!properties.allowedDomainSet().isEmpty()) {
                try {
                    SsrfGuard.requireAllowedDomain(img, properties.allowedDomainSet());
                } catch (ResponseStatusException ex) {
                    return null;
                }
            }
            if (!proxyConfigured) {
                dnsResolver.resolveOrReject(img.getHost());
            }

            byte[] body = fetchBytesLimited(img);
            if (body == null || body.length == 0) {
                return null;
            }
            String detected = "application/octet-stream";
            if (mediaTypeDetector != null) {
                detected = mediaTypeDetector.detect(new ByteArrayInputStream(body), "preview.bin");
            }
            if (!detected.startsWith("image/")) {
                return null;
            }
            String filename = detected.contains("png") ? "preview.png"
                    : detected.contains("webp") ? "preview.webp"
                    : detected.contains("gif") ? "preview.gif" : "preview.jpg";
            // Content-Type du MultipartFile ignoré par AttachmentService (Tika) — hint fichier seulement.
            MultipartFile file = new BytesMultipartFile(filename, "application/octet-stream", body);
            var info = attachmentService.upload(jwt, documentId, file);
            return info.id();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetch binaire borné (même client, mêmes gardes, redirections revalidées). */
    byte[] fetchBytesLimited(URI start) throws IOException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            SsrfGuard.requireHttpUrl(current.toString());
            if (!proxyConfigured) {
                dnsResolver.resolveOrReject(current.getHost());
            }
            HttpGet get = new HttpGet(current);
            get.setHeader("User-Agent", "SocleLinkPreview/1.0");
            final URI hopUri = current;
            final int hopIdx = hop;
            try {
                return httpClient.execute(get, resp -> {
                    int code = resp.getCode();
                    if (code >= 300 && code < 400) {
                        var locHeader = resp.getFirstHeader("Location");
                        String loc = locHeader != null ? locHeader.getValue() : null;
                        if (loc == null || hopIdx == MAX_REDIRECTS) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Aperçu de lien refusé : redirection invalide");
                        }
                        throw new RedirectSignal(hopUri.resolve(loc));
                    }
                    if (code != 200) {
                        EntityUtils.consumeQuietly(resp.getEntity());
                        return null;
                    }
                    HttpEntity entity = resp.getEntity();
                    if (entity == null) {
                        return null;
                    }
                    try (InputStream in = entity.getContent()) {
                        return readLimited(in, MAX_BYTES);
                    }
                });
            } catch (RedirectSignal rs) {
                current = rs.target();
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.BAD_REQUEST
                        && e.getReason() != null
                        && e.getReason().contains("trop volumineux")) {
                    throw e;
                }
                return null;
            }
        }
        return null;
    }

    boolean isProxyConfigured() {
        return proxyConfigured;
    }

    ValidatingDnsResolver dnsResolver() {
        return dnsResolver;
    }

    /** MultipartFile minimal pour stocker une vignette sans dépendance test. */
    private static final class BytesMultipartFile implements MultipartFile {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;

        BytesMultipartFile(String filename, String contentType, byte[] bytes) {
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }

    private static String extractTitle(String html, String fallbackHost) {
        try {
            Document doc = Jsoup.parse(html);
            var og = doc.selectFirst("meta[property=og:title]");
            if (og != null && !og.attr("content").isBlank()) {
                return og.attr("content").trim();
            }
            String t = doc.title();
            if (t != null && !t.isBlank()) {
                return t.trim();
            }
        } catch (Exception ignored) {
            // fallback
        }
        return fallbackHost;
    }

    static byte[] readLimited(InputStream in, int max) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes(max + 1);
            if (buf.length > max) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Aperçu de lien refusé : contenu trop volumineux");
            }
            return buf;
        }
    }

    private void enforceRateLimit(UUID userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        RateWindow w = rate.compute(userId, (id, prev) -> {
            if (prev == null || prev.minute != minute) {
                return new RateWindow(minute, new AtomicInteger(1));
            }
            prev.count.incrementAndGet();
            return prev;
        });
        if (w.count.get() > Math.max(1, properties.getRateLimitPerMinute())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite d'aperçus atteinte");
        }
    }

    private static String normalize(URI uri) {
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        String q = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + path + q;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record FetchResult(String html, String ogImage) {}

    private record RateWindow(long minute, AtomicInteger count) {}

    /** Redirection manuelle hors du callback execute. */
    private static final class RedirectSignal extends RuntimeException {
        private final URI target;

        RedirectSignal(URI target) {
            this.target = target;
        }

        URI target() {
            return target;
        }
    }
}
