// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import eu.socle.attachment.AttachmentService;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRepository;
import eu.socle.linkpreview.LinkPreviewDtos.PreviewView;
import eu.socle.user.UserSyncService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Activé : whitelist + anti-SSRF (DNS, IP privées, redirections, ports 80/443).
 */
@Service
public class LinkPreviewService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 1_048_576;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final LinkPreviewProperties properties;
    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final Clock clock;
    private AttachmentService attachmentService;

    /** Compteur simple par utilisateur (fenêtre 1 minute). */
    private final ConcurrentHashMap<UUID, RateWindow> rate = new ConcurrentHashMap<>();

    public LinkPreviewService(
            LinkPreviewProperties properties,
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            AuditService auditService,
            Clock clock
    ) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
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
        SsrfGuard.resolveAndRejectPrivate(start.getHost());

        String norm = normalize(start);
        String hash = sha256(norm);
        Optional<PreviewView> cached = readCache(hash);
        if (cached.isPresent()) {
            return cached.get();
        }

        if (documentId != null) {
            documentRepository.findActiveById(documentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
            authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
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

    private Optional<PreviewView> readCache(String hash) {
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

    FetchResult fetchHtml(URI start) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.getProxyUrl() != null && !properties.getProxyUrl().isBlank()) {
            URI proxy = URI.create(properties.getProxyUrl().trim());
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(),
                    proxy.getPort() > 0 ? proxy.getPort() : 8080)));
        }
        HttpClient client = builder.build();

        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            SsrfGuard.requireHttpUrl(current.toString());
            SsrfGuard.requireAllowedDomain(current, properties.allowedDomainSet());
            List<InetAddress> resolved = SsrfGuard.resolveAndRejectPrivate(current.getHost());
            // Revérification explicite (défense en profondeur)
            for (InetAddress a : resolved) {
                if (SsrfGuard.isBlocked(a)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Aperçu de lien refusé : adresse réseau privée ou réservée");
                }
            }

            HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "SocleLinkPreview/1.0")
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Échec de récupération de l'aperçu");
            }
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String loc = resp.headers().firstValue("location").orElse(null);
                if (loc == null || hop == MAX_REDIRECTS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : redirection invalide");
                }
                current = start.resolve(loc);
                continue;
            }
            if (code < 200 || code >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Aperçu de lien : HTTP " + code);
            }
            String ctype = resp.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!ctype.startsWith("text/html")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : text/html uniquement");
            }
            byte[] body;
            try {
                body = readLimited(resp.body(), MAX_BYTES);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Échec de lecture de l'aperçu");
            }
            String html = new String(body, StandardCharsets.UTF_8);
            String ogImage = null;
            try {
                Document doc = Jsoup.parse(html, current.toString());
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
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : trop de redirections");
    }

    private UUID tryStoreThumbnail(Jwt jwt, UUID documentId, String imageUrl) {
        try {
            URI img = SsrfGuard.requireHttpUrl(imageUrl);
            // Même garde pour la vignette (pas de domaine hors whitelist)
            if (!properties.allowedDomainSet().isEmpty()) {
                // autoriser tout domaine de la whitelist OU même hôte que la page
                try {
                    SsrfGuard.requireAllowedDomain(img, properties.allowedDomainSet());
                } catch (ResponseStatusException ex) {
                    return null;
                }
            }
            SsrfGuard.resolveAndRejectPrivate(img.getHost());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(img).timeout(TIMEOUT).GET().build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0
                    || resp.body().length > MAX_BYTES) {
                return null;
            }
            String ctype = resp.headers().firstValue("content-type").orElse("image/png");
            if (!ctype.startsWith("image/")) {
                return null;
            }
            MultipartFile file = new BytesMultipartFile(
                    "preview.jpg", ctype.split(";")[0].trim(), resp.body());
            var info = attachmentService.upload(jwt, documentId, file);
            return info.id();
        } catch (Exception e) {
            return null;
        }
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

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes(max + 1);
            if (buf.length > max) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aperçu de lien refusé : contenu trop volumineux");
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
}
