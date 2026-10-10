// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import dev.openfga.sdk.api.client.model.ClientWriteResponse;
import eu.socle.SocleBackendApplication;
import eu.socle.audit.AuditActions;
import eu.socle.web.ApiErrors;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jetons d'accès personnels — chaîne HTTP réelle (Postgres Testcontainers, JWT RS256 signé,
 * {@code AuthenticationManagerResolver}, filtres PAT, OpenFGA mocké mais évalué à chaque requête).
 */
@SpringBootTest(classes = SocleBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(PersonalAccessTokenHttpTest.SignedJwtTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class PersonalAccessTokenHttpTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String USER_SUB = "44444444-4444-4444-4444-444444444444";
    static final String ADMIN_SUB = "55555555-5555-5555-5555-555555555555";
    static final UUID USER_ID = UUID.fromString(USER_SUB);
    static final UUID ADMIN_ID = UUID.fromString(ADMIN_SUB);

    static RSAKey rsaKey;
    static final ObjectMapper mapper = new ObjectMapper();

    static {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("pat-http-test").generate();
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
        registry.add("socle.identity.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:9/jwks");
    }

    @TestConfiguration
    static class SignedJwtTestConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatService patService;

    @MockBean WorkflowClient workflowClient;
    @MockBean OpenFgaClient openFgaClient;

    final AtomicBoolean fgaAllowed = new AtomicBoolean(true);
    ListAppender<ILoggingEvent> logs;
    Level previousRootLevel;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("SET session_replication_role = replica");
        try {
            jdbc.update("DELETE FROM audit_log_events");
            jdbc.update("DELETE FROM notifications");
            jdbc.update("DELETE FROM personal_access_tokens");
            jdbc.update("DELETE FROM space_owners");
            jdbc.update("DELETE FROM spaces");
            jdbc.update("DELETE FROM user_platform_roles");
            jdbc.update("DELETE FROM user_identities");
            jdbc.update("DELETE FROM users");
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
        seedUser(USER_ID, USER_SUB, "camille@example.com", false);
        seedUser(ADMIN_ID, ADMIN_SUB, "admin@example.com", true);

        fgaAllowed.set(true);
        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(empty));
        when(openFgaClient.check(any(ClientCheckRequest.class))).thenAnswer(inv -> {
            ClientCheckResponse r = mock(ClientCheckResponse.class);
            when(r.getAllowed()).thenReturn(fgaAllowed.get());
            return CompletableFuture.completedFuture(r);
        });
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            ClientBatchCheckRequest req = inv.getArgument(0);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                results.add(new ClientBatchCheckSingleResponse(fgaAllowed.get(), item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });
        when(openFgaClient.write(any(), any())).thenReturn(
                CompletableFuture.completedFuture(mock(ClientWriteResponse.class)));

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousRootLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(previousRootLevel);
    }

    @Test
    void readScope_get200_post403ScopeInsufficient() throws Exception {
        String pat = createToken(jwt(USER_SUB), "lecture", "read", 30);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_SUB));
        mockMvc.perform(post("/api/v1/spaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Interdit\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_SCOPE_INSUFFICIENT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spaces", Long.class)).isZero();
    }

    @Test
    void readWriteScope_post201_auditedWithAuthMethodPatAndTokenId() throws Exception {
        MvcResult created = createTokenResult(jwt(USER_SUB), "écriture", "read_write", 30);
        String pat = mapper.readTree(created.getResponse().getContentAsString()).path("plaintext").asText();
        String patId = mapper.readTree(created.getResponse().getContentAsString()).path("token").path("id").asText();

        mockMvc.perform(post("/api/v1/spaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Via PAT\"}"))
                .andExpect(status().isCreated());

        JsonNode meta = awaitAuditMetadata(AuditActions.SPACE_CREATED);
        assertThat(meta.path("authMethod").asText()).isEqualTo("pat");
        assertThat(meta.path("patId").asText()).isEqualTo(patId);
        assertThat(jdbc.queryForObject(
                "SELECT actor_id FROM audit_log_events WHERE action = ?", UUID.class, AuditActions.SPACE_CREATED))
                .isEqualTo(USER_ID);
    }

    @Test
    void createAndRevoke_audited_noSecretAnywhere_listNeverExposesHash() throws Exception {
        String pat = createToken(jwt(USER_SUB), "Script CI", "read", 7);
        String secret = PatTokenFormat.parse(pat).orElseThrow().secret();

        MvcResult list = mockMvc.perform(get("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Script CI"))
                .andExpect(jsonPath("$[0].last4").value(pat.substring(pat.length() - 4)))
                .andExpect(jsonPath("$[0].scope").value("read"))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].expiresAt").exists())
                .andReturn();
        String listBody = list.getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(secret).doesNotContainIgnoringCase("hash").doesNotContain("lookup");

        String rowDump = jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM personal_access_tokens t", String.class);
        assertThat(rowDump).doesNotContain(secret).doesNotContain(pat);
        byte[] storedHash = jdbc.queryForObject("SELECT token_hash FROM personal_access_tokens", byte[].class);
        assertThat(storedHash).hasSize(32);

        UUID id = jdbc.queryForObject("SELECT id FROM personal_access_tokens", UUID.class);
        mockMvc.perform(delete("/api/v1/me/tokens/" + id).header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB)))
                .andExpect(status().isNoContent());

        JsonNode createdMeta = awaitAuditMetadata(AuditActions.PAT_CREATED);
        assertThat(createdMeta.path("name").asText()).isEqualTo("Script CI");
        assertThat(createdMeta.path("scope").asText()).isEqualTo("read");
        assertThat(createdMeta.path("last4").asText()).isEqualTo(pat.substring(pat.length() - 4));
        assertThat(createdMeta.path("expiresAt").asText()).isNotBlank();
        JsonNode revokedMeta = awaitAuditMetadata(AuditActions.PAT_REVOKED);
        assertThat(revokedMeta.path("reason").asText()).isEqualTo(PatService.REASON_MANUAL);

        String allAudit = String.join("\n", jdbc.queryForList(
                "SELECT metadata::text FROM audit_log_events", String.class));
        assertThat(allAudit).doesNotContain(secret).doesNotContain(pat);
    }

    @Test
    void expiry_bounds_400_andLimitTen_409() throws Exception {
        for (int days : new int[] {0, 91}) {
            mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"scope\":\"read\",\"expiresInDays\":" + days + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiErrors.PAT_EXPIRY_INVALID));
        }
        mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sans expiration\",\"scope\":\"read\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_EXPIRY_INVALID));
        createToken(jwt(USER_SUB), "un jour", "read", 1);
        for (int i = 1; i < PatService.MAX_ACTIVE_TOKENS; i++) {
            createToken(jwt(USER_SUB), "t" + i, "read", 90);
        }
        mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"onzième\",\"scope\":\"read\",\"expiresInDays\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_LIMIT_REACHED));
    }

    @Test
    void expired_401_revoked_401_generic() throws Exception {
        String expired = createToken(jwt(USER_SUB), "expiré", "read", 30);
        String revoked = createToken(jwt(USER_SUB), "révoqué", "read", 30);
        jdbc.update("""
                UPDATE personal_access_tokens
                   SET created_at = now() - interval '31 days', expires_at = now() - interval '1 second'
                 WHERE name = 'expiré'
                """);
        UUID revokedId = jdbc.queryForObject(
                "SELECT id FROM personal_access_tokens WHERE name = 'révoqué'", UUID.class);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + revoked))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/me/tokens/" + revokedId).header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB)))
                .andExpect(status().isNoContent());

        String wrongSecret = revoked.substring(0, revoked.length() - 43) + "A".repeat(43);
        String unknownLookup = "pat_ZZZZZZZZZZZZ_" + "B".repeat(43);
        List<String> challenges = new ArrayList<>();
        for (String bad : List.of(expired, revoked, wrongSecret, unknownLookup, "pat_malformed")) {
            MvcResult r = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertThat(r.getResponse().getContentAsString()).isEmpty();
            challenges.add(r.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));
        }
        assertThat(challenges).allMatch(c -> c != null && c.contains("invalid_token"));
        assertThat(challenges.stream().distinct()).as("cause non divulguée").hasSize(1);
    }

    @Test
    void userDisabledByAdmin_401_andTokensRevokedInSameTransaction() throws Exception {
        String pat = createToken(jwt(USER_SUB), "à révoquer", "read_write", 30);
        createToken(jwt(USER_SUB), "second", "read", 30);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/disable")
                        .header(HttpHeaders.AUTHORIZATION, jwt(ADMIN_SUB)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList(
                "SELECT revoked_reason FROM personal_access_tokens WHERE user_id = ?", String.class, USER_ID))
                .hasSize(2).containsOnly(PatService.REASON_USER_DISABLED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log_events
                 WHERE action = ? AND metadata->>'reason' = 'user_disabled' AND actor_id = ?
                """, Long.class, AuditActions.PAT_REVOKED, ADMIN_ID)).isEqualTo(2L);

        // Réactivation : les jetons restent révoqués.
        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/enable")
                        .header(HttpHeaders.AUTHORIZATION, jwt(ADMIN_SUB)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openFgaRightWithdrawnAfterCreation_immediate403() throws Exception {
        String pat = createToken(jwt(USER_SUB), "fga", "read", 30);
        UUID spaceId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/content-health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk());

        fgaAllowed.set(false);
        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/content-health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPat_onAdminRoutes_403PatNotAllowed_whileAdminJwtOk() throws Exception {
        String pat = createToken(jwt(ADMIN_SUB), "admin", "read_write", 30);

        mockMvc.perform(get("/api/v1/admin/overview").header(HttpHeaders.AUTHORIZATION, jwt(ADMIN_SUB)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/overview").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        mockMvc.perform(get(URI.create("/api/v1/%61dmin/overview")).header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/disable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, USER_ID))
                .isEqualTo("active");
    }

    @Test
    void createTokenWithPat_403_exportWithPat_403() throws Exception {
        String pat = createToken(jwt(USER_SUB), "rw", "read_write", 30);

        mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rebond\",\"scope\":\"read_write\",\"expiresInDays\":90}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        mockMvc.perform(get("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        mockMvc.perform(get("/api/v1/me/export").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_access_tokens", Long.class)).isEqualTo(1L);
    }

    @Test
    void patNeverCreatesUser_unknownIdentityIsGeneric401() throws Exception {
        String pat = createToken(jwt(USER_SUB), "orphelin", "read", 30);
        long usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        jdbc.update("DELETE FROM user_identities WHERE user_id = ?", USER_ID);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities", Long.class)).isEqualTo(1L);
    }

    @Test
    void lastUsedAt_setOnUse_atMostOncePerMinute() throws Exception {
        String pat = createToken(jwt(USER_SUB), "usage", "read", 30);
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM personal_access_tokens", Instant.class)).isNull();

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk());
        java.sql.Timestamp first = jdbc.queryForObject(
                "SELECT last_used_at FROM personal_access_tokens", java.sql.Timestamp.class);
        assertThat(first).isNotNull();
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM personal_access_tokens", java.sql.Timestamp.class))
                .isEqualTo(first);
    }

    @Test
    void expiryNotification_sevenDaysBefore_oncePerToken_readableWithReadWritePat() throws Exception {
        String soon = createToken(jwt(USER_SUB), "bientôt", "read_write", 5);
        createToken(jwt(USER_SUB), "plus tard", "read", 30);

        assertThat(patService.notifyExpiringTokens()).isEqualTo(1);
        assertThat(patService.notifyExpiringTokens()).as("une seule par jeton").isZero();
        Map<String, Object> notif = jdbc.queryForMap(
                "SELECT id, user_id, payload->>'name' AS name, payload->>'last4' AS last4 "
                        + "FROM notifications WHERE type = 'pat_expiring'");
        assertThat(notif.get("user_id")).isEqualTo(USER_ID);
        assertThat(notif.get("name")).isEqualTo("bientôt");
        assertThat(notif.get("last4")).isEqualTo(soon.substring(soon.length() - 4));

        mockMvc.perform(post("/api/v1/notifications/" + notif.get("id") + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + soon))
                .andExpect(status().isOk());
    }

    @Test
    void noSecretInLogs_capturedAppender() throws Exception {
        String pat = createToken(jwt(USER_SUB), "journal", "read_write", 30);
        String secret = PatTokenFormat.parse(pat).orElseThrow().secret();
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        String wrong = pat.substring(0, pat.length() - 1) + (pat.endsWith("A") ? "B" : "A");
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrong))
                .andExpect(status().isUnauthorized());
        UUID id = jdbc.queryForObject("SELECT id FROM personal_access_tokens", UUID.class);
        mockMvc.perform(delete("/api/v1/me/tokens/" + id).header(HttpHeaders.AUTHORIZATION, jwt(USER_SUB)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pat))
                .andExpect(status().isUnauthorized());
        awaitAuditMetadata(AuditActions.PAT_REVOKED);

        assertThat(logs.list).as("événements capturés").isNotEmpty();
        String secretTail = secret.substring(8);
        for (ILoggingEvent e : List.copyOf(logs.list)) {
            String text = render(e);
            assertThat(text).as("log %s", e.getLoggerName())
                    .doesNotContain(secretTail)
                    .doesNotContain(wrong.substring(wrong.length() - 35));
        }
    }

    private static String render(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getFormattedMessage()));
        if (e.getArgumentArray() != null) {
            for (Object a : e.getArgumentArray()) {
                sb.append(' ').append(a);
            }
        }
        sb.append(' ').append(e.getMDCPropertyMap());
        for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
            sb.append(' ').append(t.getClassName()).append(':').append(t.getMessage());
        }
        return sb.toString();
    }

    private String createToken(String bearerJwt, String name, String scope, int days) throws Exception {
        MvcResult r = createTokenResult(bearerJwt, name, scope, days);
        String token = mapper.readTree(r.getResponse().getContentAsString()).path("plaintext").asText();
        assertThat(token).matches("^pat_[0-9A-Za-z]{12}_[0-9A-Za-z]{43}$");
        return token;
    }

    private MvcResult createTokenResult(String bearerJwt, String name, String scope, int days) throws Exception {
        return mockMvc.perform(post("/api/v1/me/tokens").header(HttpHeaders.AUTHORIZATION, bearerJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", name, "scope", scope, "expiresInDays", days))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token.status").value("active"))
                .andExpect(jsonPath("$.token.scope").value(scope))
                .andReturn();
    }

    private JsonNode awaitAuditMetadata(String action) throws Exception {
        for (int i = 0; i < 60; i++) {
            List<String> rows = jdbc.queryForList(
                    "SELECT metadata::text FROM audit_log_events WHERE action = ? ORDER BY id DESC",
                    String.class, action);
            if (!rows.isEmpty()) {
                return mapper.readTree(rows.get(0));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("audit absent : " + action);
    }

    private void seedUser(UUID id, String subject, String email, boolean admin) {
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

    private static String jwt(String sub) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", sub + "@example.com")
                .claim("email_verified", true)
                .claim("name", "Test " + sub.substring(0, 4))
                .claim("realm_access", Map.of("roles", List.of("contributeur")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return "Bearer " + jwt.serialize();
    }
}
