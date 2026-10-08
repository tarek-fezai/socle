// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.admin.AdminOverviewDtos.AdminOverviewView;
import eu.socle.admin.AdminOverviewService;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.identity.IdentityProperties;
import eu.socle.testsupport.MigratedPostgres;
import eu.socle.user.UserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * IT AdminOverviewService — JDBC réel. AdminOverviewService non mocké.
 * LicenceService réel (ctor package-private de test) sans licence → évaluation.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOverviewServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;
    static KeyPair testKeyPair;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    IdentityProperties identityProperties;
    LicenceService licenceService;
    AdminOverviewService overviewService;
    Jwt jwt;

    @BeforeAll
    static void migrate() throws Exception {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status) VALUES (?, 'admin@test', 'Admin', 'active')",
                ADMIN);
        testKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM instance_licence");
        jdbc.update("DELETE FROM spaces");
        jdbc.update("DELETE FROM users WHERE id <> ?", ADMIN);
        jdbc.update("UPDATE users SET status = 'active', is_system_account = false WHERE id = ?", ADMIN);

        identityProperties = new IdentityProperties();
        identityProperties.setIssuerUri("https://idp.example.com/realms/socle");
        identityProperties.setClientId("socle-frontend");
        identityProperties.setBootstrapAdminSubjects(List.of("bootstrap-admin-sub"));

        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);

        licenceService = new LicenceService(
                jdbc,
                identityFacade,
                identityProperties,
                auditService,
                new ObjectMapper(),
                CLOCK,
                testKeyPair.getPublic());

        overviewService = new AdminOverviewService(jdbc, identityProperties, licenceService);
        jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(ADMIN.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(60))
                .build();
    }

    @Test
    void counts_excludeSystemInactiveAndDeletedSpaces_evaluationWithoutLicence() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status, is_system_account) VALUES (?, 'a@t', 'A', 'active', false)",
                active);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status, is_system_account) VALUES (?, 'i@t', 'I', 'suspended', false)",
                inactive);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status, is_system_account) VALUES (?, 's@t', 'S', 'active', true)",
                system);

        UUID spaceLive = UUID.randomUUID();
        UUID spaceDeleted = UUID.randomUUID();
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Live')", spaceLive);
        jdbc.update("INSERT INTO spaces (id, name, deleted_at) VALUES (?, 'Gone', now())", spaceDeleted);

        AdminOverviewView view = overviewService.overview(jwt);

        assertThat(view.userCount()).isEqualTo(2);
        assertThat(view.spaceCount()).isEqualTo(1);
        assertThat(view.plan().evaluationMode()).isTrue();
        assertThat(view.oidc().issuer()).isEqualTo("https://idp.example.com/realms/socle");
        assertThat(view.oidc().clientId()).isEqualTo("socle-frontend");
        assertThat(view.oidc().status()).isEqualTo("connected");
    }
}
