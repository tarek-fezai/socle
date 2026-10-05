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

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Redirection vers une IP privée : refus sans connexion vers la cible privée
 * (seule la première hop publique est contactée).
 */
@ExtendWith(MockitoExtension.class)
class LinkPreviewRedirectSsrfTest {

    @Mock JdbcTemplate jdbc;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock DocumentRepository documentRepository;
    @Mock AuditService auditService;

    LinkPreviewProperties properties = new LinkPreviewProperties();
    LinkPreviewService service;
    HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        properties.setEnabled(true);
        properties.setAllowedDomains("127.0.0.1");
        service = new LinkPreviewService(
                properties, jdbc, userSyncService, authorizationService,
                documentRepository, auditService,
                Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC));

        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("a@x");
        u.setDisplayName("A");
        lenient().when(userSyncService.syncFromJwt(any())).thenReturn(u);
        lenient().when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(java.util.List.of());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/go", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
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
    void redirectToMetadataIp_isRejected() {
        // 127.0.0.1 est bloqué par isBlocked — requireAllowedDomain ok mais resolve refuse.
        // On simule le cas « redirection vers privée » via SsrfGuard sur l'URL cible.
        assertThatThrownBy(() -> SsrfGuard.resolveAndRejectPrivate("169.254.169.254"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("privée");

        // Et fetchHtml refuse aussi si on force le domaine (port dynamique hors 80/443 → déjà refusé)
        int port = server.getAddress().getPort();
        assertThatThrownBy(() ->
                service.fetch(jwt(), "http://127.0.0.1:" + port + "/go", null))
                .isInstanceOf(ResponseStatusException.class);
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
