// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import eu.socle.SocleBackendApplication;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.identity.IdentityProperties;
import eu.socle.web.ApiErrors;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaîne HTTP réelle : Bearer JWT signé → JwtDecoder → {@code IdentityJwtConfig} /
 * {@code UserSyncService} / licence — pas de {@code SecurityMockMvcRequestPostProcessors.jwt()}.
 */
@SpringBootTest(classes = SocleBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(LicenceBootstrapHttpIT.SignedJwtTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class LicenceBootstrapHttpIT {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String ISSUER_OTHER = "https://other-idp.example/realms/other";
    static final String BOOTSTRAP_SUB = "11111111-1111-1111-1111-111111111111";
    static final String EXISTING_SUB = "22222222-2222-2222-2222-222222222222";
    static final String NEW_SUB = "33333333-3333-3333-3333-333333333333";

    static RSAKey rsaKey;
    static KeyPair licenceKeyPair;
    static ObjectMapper mapper = new ObjectMapper();

    static {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("licence-http-it").generate();
            KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
            licenceKeyPair = g.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("socle.temporal.worker-enabled", () -> "false");
        registry.add("socle.openfga.auto-init", () -> "false");
        registry.add("socle.identity.role-source", () -> "BOTH");
        registry.add("socle.identity.bootstrap-admin-subjects[0]", () -> BOOTSTRAP_SUB);
        registry.add("socle.identity.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:9/jwks");
    }

    @TestConfiguration
    static class SignedJwtTestConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        }

        /** Clé Ed25519 de TEST — jamais la clé de production embarquée. */
        @Bean
        @Primary
        LicenceService licenceService(
                JdbcTemplate jdbc,
                IdentityFacade identityFacade,
                IdentityProperties identityProperties,
                AuditService auditService,
                ObjectMapper objectMapper,
                Clock clock
        ) {
            return new LicenceService(
                    jdbc, identityFacade, identityProperties, auditService, objectMapper, clock,
                    licenceKeyPair.getPublic());
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @MockBean WorkflowClient workflowClient;
    @MockBean OpenFgaClient openFgaClient;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeEach
    void clean() throws Exception {
        jdbc.execute("SET session_replication_role = replica");
        try {
            jdbc.update("DELETE FROM audit_log_events");
            jdbc.update("DELETE FROM instance_licence");
            jdbc.update("DELETE FROM user_platform_roles");
            jdbc.update("DELETE FROM user_identities");
            jdbc.update("DELETE FROM users");
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }

        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(empty));
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            ClientBatchCheckRequest req = inv.getArgument(0);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                results.add(new ClientBatchCheckSingleResponse(
                        true, item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });
    }

    @Test
    void bootstrapOnly_noLicence_homeAndLicenceOk_importReachable() throws Exception {
        seedUser(BOOTSTRAP_SUB, "contributeur@example.com", true);

        String token = bearer(BOOTSTRAP_SUB, "contributeur@example.com", "Camille Contributeur");

        mockMvc.perform(get("/api/v1/home").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/licence").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(LicenceService.STATUS_ABSENTE));

        String licenceJson = signedLicence("LIC-IT", "Socle IT", "Entreprise",
                "2026-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 10);
        mockMvc.perform(post("/api/v1/admin/licence/import")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceJson\":" + mapper.writeValueAsString(licenceJson) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(LicenceService.STATUS_VALIDE))
                .andExpect(jsonPath("$.maxUsers").value(10));
    }

    @Test
    void noLicence_newNonBootstrap_403ProblemJson_noRowCreated() throws Exception {
        seedUser(BOOTSTRAP_SUB, "contributeur@example.com", true);
        long before = countUsers();

        expectNoLicenceForbidden(bearer(NEW_SUB, "newbie@example.com", "Newbie"));

        assertThat(countUsers()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_identities WHERE subject = ?", Long.class, NEW_SUB))
                .isZero();
        // Audit async — poll court.
        long denied = 0;
        for (int i = 0; i < 40; i++) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log_events WHERE action = 'auth.access_denied'",
                    Long.class);
            denied = n == null ? 0 : n;
            if (denied > 0) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(denied).isGreaterThan(0);
    }

    @Test
    void noLicence_existingNonBootstrap_200() throws Exception {
        seedUser(EXISTING_SUB, "auditeur@example.com", false);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(EXISTING_SUB, "auditeur@example.com", "Ada Auditeur")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EXISTING_SUB));
    }

    @Test
    void sameSubDifferentIssuer_403IdentityConflict_noLink_originalStillOk() throws Exception {
        // Compte A : issuer1 / sub X (id = sub)
        seedUser(EXISTING_SUB, "alice@example.com", false);
        UUID userId = UUID.fromString(EXISTING_SUB);
        long identitiesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM user_identities WHERE user_id = ?", Long.class, userId);

        // JWT issuer2 / même sub → refus, aucune liaison implicite
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(ISSUER_OTHER, EXISTING_SUB, "alice@evil.com", "Alice Evil")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.IDENTITY_CONFLICT))
                .andExpect(jsonPath("$.detail").value(
                        "Identité déjà associée à un autre compte : contactez l'administrateur"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_identities WHERE user_id = ?", Long.class, userId))
                .isEqualTo(identitiesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_identities WHERE issuer = ?", Long.class, ISSUER_OTHER))
                .isZero();

        // A toujours accessible via issuer1
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(EXISTING_SUB, "alice@example.com", "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EXISTING_SUB));
    }

    @Test
    void maxUsersReached_new403_existing200() throws Exception {
        seedUser(BOOTSTRAP_SUB, "contributeur@example.com", true);
        seedUser(EXISTING_SUB, "auditeur@example.com", false);

        String adminTok = bearer(BOOTSTRAP_SUB, "contributeur@example.com", "Camille Contributeur");
        String licenceJson = signedLicence("LIC-SEAT", "Socle IT", "Entreprise",
                "2026-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 2);
        mockMvc.perform(post("/api/v1/admin/licence/import")
                        .header(HttpHeaders.AUTHORIZATION, adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceJson\":" + mapper.writeValueAsString(licenceJson) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUsers").value(2));

        // 2 actifs (bootstrap + existing) = plafond → nouveau refusé, existant OK.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(EXISTING_SUB, "auditeur@example.com", "Ada Auditeur")))
                .andExpect(status().isOk());

        long before = countUsers();
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        bearer(NEW_SUB, "newbie@example.com", "Newbie")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.LICENCE_USER_LIMIT))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Limite d'utilisateurs atteinte")));
        assertThat(countUsers()).isEqualTo(before);
    }

    private void expectNoLicenceForbidden(String bearerToken) throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.LICENCE_USER_LIMIT))
                .andExpect(jsonPath("$.detail").value(
                        "Aucune licence valide installée : contactez l'administrateur"));
    }

    private void seedUser(String subject, String email, boolean admin) {
        UUID id = UUID.fromString(subject);
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, last_login_at)
                VALUES (?, ?, ?, 'active', now())
                """, id, email, email);
        jdbc.update("""
                INSERT INTO user_identities (user_id, issuer, subject, linked_at)
                VALUES (?, ?, ?, now())
                """, id, ISSUER, subject);
        if (admin) {
            jdbc.update("""
                    INSERT INTO user_platform_roles (user_id, role, granted_at)
                    VALUES (?, 'ADMINISTRATEUR_SYSTEME', now())
                    """, id);
        }
    }

    private long countUsers() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    private static String signedLicence(
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
        s.initSign(licenceKeyPair.getPrivate());
        s.update(payload);
        ObjectNode out = mapper.createObjectNode();
        out.put("licenseId", id);
        out.put("licensee", licensee);
        out.put("edition", edition);
        out.put("issuedAt", issued);
        out.put("expiresAt", expires);
        out.put("maxUsers", maxUsers);
        out.put("signature", Base64.getEncoder().encodeToString(s.sign()));
        return mapper.writeValueAsString(out);
    }

    private static String bearer(String sub, String email, String name) throws Exception {
        return bearer(ISSUER, sub, email, name);
    }

    private static String bearer(String issuer, String sub, String email, String name) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", name)
                .claim("realm_access", Map.of("roles", List.of("contributeur")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return "Bearer " + jwt.serialize();
    }
}
