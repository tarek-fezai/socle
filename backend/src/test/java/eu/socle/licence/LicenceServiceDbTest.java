// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Licence : signature Ed25519, expiration, limite de sièges, import admin.
 * Aucune connexion sortante (vérification hors ligne).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenceServiceDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;
    static KeyPair keyPair;
    static ObjectMapper mapper = new ObjectMapper();

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    LicenceService service;
    Jwt jwt;

    @BeforeAll
    static void migrate() throws Exception {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'admin@test', 'Admin', 'active')",
                ADMIN);
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        keyPair = g.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM instance_licence");
        jdbc.update("DELETE FROM users WHERE id <> ?", ADMIN);
        jdbc.update("UPDATE users SET status = 'active' WHERE id = ?", ADMIN);

        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(ADMIN.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();

        service = new LicenceService(jdbc, identityFacade, auditService, mapper, CLOCK, keyPair.getPublic());
    }

    @Test
    void validSignature_importsAndShowsValide() throws Exception {
        String json = signedLicence("LIC-1", "Acme", "Entreprise",
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 10);

        var view = service.importLicence(jwt, new LicenceDtos.ImportLicenceRequest(json));

        assertThat(view.status()).isEqualTo(LicenceService.STATUS_VALIDE);
        assertThat(view.maxUsers()).isEqualTo(10);
        assertThat(view.licenseId()).isEqualTo("LIC-1");
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.LICENCE_IMPORTED),
                eq("instance_licence"), isNull(), anyMap(), isNull());
    }

    @Test
    void invalidSignature_rejected() throws Exception {
        String json = signedLicence("LIC-1", "Acme", "Entreprise",
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 10);
        JsonNode root = mapper.readTree(json);
        ((ObjectNode) root).put("signature", Base64.getEncoder().encodeToString(new byte[64]));

        assertThatThrownBy(() -> service.importLicence(jwt,
                new LicenceDtos.ImportLicenceRequest(root.toString())))
                .isInstanceOf(CodedStatusException.class)
                .extracting(ex -> ((CodedStatusException) ex).getCode())
                .isEqualTo(ApiErrors.LICENCE_REJECTED);
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.LICENCE_REJECTED),
                eq("instance_licence"), isNull(), anyMap(), isNull());
    }

    @Test
    void tamperedPayload_rejected() throws Exception {
        String json = signedLicence("LIC-1", "Acme", "Entreprise",
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 10);
        JsonNode root = mapper.readTree(json);
        ((ObjectNode) root).put("maxUsers", 9999);

        assertThatThrownBy(() -> service.importLicence(jwt,
                new LicenceDtos.ImportLicenceRequest(root.toString())))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> assertThat(((CodedStatusException) ex).getCode())
                        .isEqualTo(ApiErrors.LICENCE_REJECTED));
    }

    @Test
    void expiredLicence_fallsBackToEvaluationLimit() throws Exception {
        String json = signedLicence("LIC-OLD", "Acme", "Entreprise",
                "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", 100);
        service.importLicence(jwt, new LicenceDtos.ImportLicenceRequest(json));

        assertThat(service.view().status()).isEqualTo(LicenceService.STATUS_EXPIREE);
        assertThat(service.view().effectiveMaxUsers()).isEqualTo(LicenceService.EVALUATION_MAX_USERS);
    }

    @Test
    void evaluationMode_blocksSixthActiveUser() {
        // ADMIN already active → create 4 more = 5 active
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, ?, 'U', 'active')",
                    id, "u" + i + "@t");
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status='active' AND COALESCE(is_system_account,false)=false",
                Long.class)).isEqualTo(5);

        assertThatThrownBy(() -> service.assertCanCreateUser())
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.LICENCE_USER_LIMIT);
                    assertThat(c.getStatusCode().value()).isEqualTo(403);
                });
    }

    @Test
    void existingUsersStillAllowedWhenAtLimit() {
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, ?, 'U', 'active')",
                    UUID.randomUUID(), "x" + i + "@t");
        }
        // Pas d'exception pour la lecture / les existants — seule la création est refusée.
        assertThat(service.view().activeUsers()).isEqualTo(5);
        assertThatThrownBy(() -> service.assertCanCreateUser()).isInstanceOf(CodedStatusException.class);
    }

    @Test
    void nonAdminImport_forbidden() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> service.importLicence(jwt,
                new LicenceDtos.ImportLicenceRequest("{}")))
                .hasMessageContaining("Administrateur");
        verify(auditService, never()).record(any(), any(Boolean.class), eq(AuditActions.LICENCE_IMPORTED),
                any(), any(), anyMap(), any());
    }

    @Test
    void verifyIsOffline_noNetwork() throws Exception {
        // Smoke : pure crypto locale
        String json = signedLicence("LIC-N", "N", "E",
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 3);
        assertThat(LicenceCrypto.verify(mapper.readTree(json), keyPair.getPublic())).isTrue();
    }

    private String signedLicence(
            String id, String licensee, String edition,
            String issued, String expires, int maxUsers
    ) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("edition", edition);
        node.put("expiresAt", expires);
        node.put("issuedAt", issued);
        node.put("licenseId", id);
        node.put("licensee", licensee);
        node.put("maxUsers", maxUsers);
        byte[] payload = LicenceCrypto.canonicalPayload(node);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keyPair.getPrivate());
        s.update(payload);
        node.put("signature", Base64.getEncoder().encodeToString(s.sign()));
        // Réordonne comme le fichier produit (champs métier + signature)
        ObjectNode out = mapper.createObjectNode();
        out.put("licenseId", id);
        out.put("licensee", licensee);
        out.put("edition", edition);
        out.put("issuedAt", issued);
        out.put("expiresAt", expires);
        out.put("maxUsers", maxUsers);
        out.put("signature", node.get("signature").asText());
        return mapper.writeValueAsString(out);
    }
}
