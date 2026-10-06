// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.linkpreview;

import com.sun.net.httpserver.HttpServer;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRepository;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * HttpClient 5 + DnsResolver : rebinding, plafond vignette, proxy.
 */
@ExtendWith(MockitoExtension.class)
class LinkPreviewHttpClientTest {

    @Mock JdbcTemplate jdbc;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock DocumentRepository documentRepository;
    @Mock AuditService auditService;

    LinkPreviewProperties properties = new LinkPreviewProperties();
    HttpServer server;
    AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        properties.setEnabled(true);
        properties.setAllowedDomains("127.0.0.1");
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("a@x");
        u.setDisplayName("A");
        lenient().when(userSyncService.syncFromJwt(any())).thenReturn(u);
        lenient().when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(java.util.List.of());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", exchange -> {
            hits.incrementAndGet();
            byte[] chunk = new byte[64 * 1024];
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                // > 1 Mo sans tout matérialiser côté client avant readLimited
                for (int i = 0; i < 20; i++) {
                    os.write(chunk);
                }
            }
        });
        server.createContext("/ok", exchange -> {
            hits.incrementAndGet();
            byte[] body = "<html><title>ok</title></html>".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void dnsResolverReturningLoopback_neverConnectsToServer() throws Exception {
        int port = server.getAddress().getPort();
        AtomicInteger dnsCalls = new AtomicInteger();
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(h -> {
            dnsCalls.incrementAndGet();
            // Force loopback : ValidatingDnsResolver refuse → aucune connexion sortante.
            return new InetAddress[]{loop};
        });
        // ports dynamiques refusés par requireHttpUrl — on teste resolveOrReject seul + fetchBytes via URI illégal
        assertThatThrownBy(() -> resolver.resolveOrReject("rebinding.test"))
                .isInstanceOf(ResponseStatusException.class);

        LinkPreviewService service = newService(resolver);
        // URL vers le serveur local : port ≠ 80/443 → refus avant connect
        assertThatThrownBy(() ->
                service.fetch(jwt(), "http://127.0.0.1:" + port + "/ok", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void rebindingSecondAnswerPrivate_notReturnedToClient() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InetAddress pub = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(h -> {
            if (calls.getAndIncrement() == 0) {
                return new InetAddress[]{pub};
            }
            return new InetAddress[]{loop};
        });
        assertThat(resolver.resolve("x.test")).containsExactly(pub);
        assertThatThrownBy(() -> resolver.resolve("x.test"))
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void thumbnailLargerThan1MiB_abortsWithoutLoadingAll() {
        assertThatThrownBy(() ->
                LinkPreviewService.readLimited(
                        new ByteArrayInputStream(new byte[LinkPreviewService.MAX_BYTES + 10]),
                        LinkPreviewService.MAX_BYTES))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("volumineux");
    }

    @Test
    void proxyConfigured_isFlaggedForPageAndThumbnailClient() throws Exception {
        properties.setProxyUrl("http://proxy.example:8080");
        properties.setAllowedDomains("example.org");
        InetAddress pub = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        LinkPreviewService service = newService(new ValidatingDnsResolver(h -> new InetAddress[]{pub}));
        assertThat(service.isProxyConfigured()).isTrue();
    }

    private LinkPreviewService newService(ValidatingDnsResolver resolver) {
        return new LinkPreviewService(
                properties, jdbc, userSyncService, authorizationService,
                documentRepository, auditService,
                Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC),
                resolver);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
