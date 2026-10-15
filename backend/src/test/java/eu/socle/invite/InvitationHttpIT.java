// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import eu.socle.SocleBackendApplication;
import eu.socle.audit.AuditActions;
import eu.socle.identity.AccessDeniedReason;
import eu.socle.identity.AccessPolicyDeniedException;
import eu.socle.licence.LicenceService;
import eu.socle.web.ApiErrors;
import io.temporal.client.WorkflowClient;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invitations — Postgres + OpenFGA réels, SMTP GreenMail, JWT RS256 signé.
 * Garde-fous a)–h) : un refus dédié + cas nominal.
 */
@SpringBootTest(classes = SocleBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(InvitationHttpIT.SignedJwtTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class InvitationHttpIT {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String ADMIN_SUB = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String INVITEE_SUB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final UUID ADMIN_ID = UUID.fromString(ADMIN_SUB);
    static final UUID TEAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final String INVITE_EMAIL = "invitee@example.com";
    static final String PEPPER = "test-invite-pepper-not-a-secret-32b!!";
    static final String SMTP_PASSWORD = "smtp-secret-for-it-never-log";
    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";

    static RSAKey rsaKey;
    static final ObjectMapper mapper = new ObjectMapper();
    static GreenMail greenMail;

    static {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("invite-http-it").generate();
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

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> openfga = new GenericContainer<>(DockerImageName.parse(OPENFGA_IMAGE))
            .withCommand("run", "--datastore-engine", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("socle.temporal.worker-enabled", () -> "false");
        registry.add("socle.openfga.auto-init", () -> "true");
        registry.add("socle.openfga.api-url",
                () -> "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080));
        registry.add("socle.openfga.state-dir",
                () -> "target/openfga-invite-it-" + System.nanoTime());
        registry.add("socle.identity.role-source", () -> "BOTH");
        registry.add("socle.identity.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:9/jwks");
        registry.add("spring.profiles.active", () -> "dev");
        registry.add("socle.invite.pepper", () -> PEPPER);
        registry.add("socle.invite.resend-min-interval-seconds", () -> "0");
        registry.add("socle.instance.public-base-url", () -> "http://127.0.0.1:3000");
        registry.add("socle.smtp.host", () -> "127.0.0.1");
        registry.add("socle.smtp.port", () -> 3025);
        registry.add("socle.smtp.tls", () -> "none");
        registry.add("socle.smtp.from", () -> "socle@test.local");
        registry.add("socle.smtp.username", () -> "smtp-user");
        registry.add("socle.smtp.password", () -> SMTP_PASSWORD);
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
    @Autowired InviteHasher hasher;
    @Autowired InvitationService invitationService;

    @MockBean WorkflowClient workflowClient;
    @MockBean LicenceService licenceService;

    ListAppender<ILoggingEvent> logs;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void startMail() {
        greenMail = new GreenMail(new ServerSetup(3025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.setUser("smtp-user", SMTP_PASSWORD);
        greenMail.start();
    }

    @AfterAll
    static void stopMail() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @BeforeEach
    void setUp() {
        doNothing().when(licenceService).assertCanCreateUser(any());
        jdbc.execute("SET session_replication_role = replica");
        try {
            jdbc.update("DELETE FROM audit_log_events");
            jdbc.update("DELETE FROM invitations");
            jdbc.update("DELETE FROM group_members");
            jdbc.update("DELETE FROM groups");
            jdbc.update("DELETE FROM personal_access_tokens");
            jdbc.update("DELETE FROM user_platform_roles");
            jdbc.update("DELETE FROM user_identities");
            jdbc.update("DELETE FROM users");
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
        seedAdmin();
        jdbc.update("INSERT INTO groups (id, name) VALUES (?, ?)", TEAM_ID, "Conformité");
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception ignored) {
            // mailbox vide
        }

        logs = new ListAppender<>();
        logs.start();
        for (String name : List.of("eu.socle", "eu.socle.invite", "eu.socle.mail")) {
            Logger log = (Logger) LoggerFactory.getLogger(name);
            log.addAppender(logs);
        }
    }

    @AfterEach
    void tearDown() {
        for (String name : List.of("eu.socle", "eu.socle.invite", "eu.socle.mail")) {
            Logger log = (Logger) LoggerFactory.getLogger(name);
            log.detachAppender(logs);
        }
    }

    @Test
    void capability_enabledWhenSmtpAndPublicUrlPresent() throws Exception {
        mockMvc.perform(get("/api/v1/admin/invitations/capability")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.missing").isEmpty());
    }

    @Test
    void happyPath_createsUserTeamAndRole() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, inviteeBearer(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("not_provisioned"));

        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(INVITE_EMAIL));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, INVITE_EMAIL)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT role FROM group_members WHERE group_id = ?", String.class, TEAM_ID))
                .isEqualTo("contributeur");
        assertThat(jdbc.queryForObject(
                "SELECT role FROM user_platform_roles u JOIN users s ON s.id = u.user_id WHERE s.email = ?",
                String.class, INVITE_EMAIL)).isEqualTo("CONTRIBUTEUR");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM invitations WHERE email = ?", String.class, INVITE_EMAIL))
                .isEqualTo("accepted");
        awaitAudit(AuditActions.INVITATION_ACCEPTED);
    }

    @Test
    void guardA_patForbidden() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        String pat = createPat();
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_NOT_ALLOWED));
        assertDeniedReason("pat");
    }

    @Test
    void guardB_emailNotVerified() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_EMAIL_UNVERIFIED));
        awaitAuditReason("email_not_verified");
    }

    @Test
    void guardC_issuerUntrusted() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer("https://evil.example", INVITEE_SUB, INVITE_EMAIL, true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_REJECTED));
        awaitAuditReason("issuer_untrusted");
    }

    @Test
    void guardD_emailMismatch() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(ISSUER, INVITEE_SUB, "other@example.com", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_EMAIL_MISMATCH));
        awaitAuditReason("email_mismatch");
    }

    @Test
    void guardE_expired() throws Exception {
        String raw = "expired-token-value-for-it-xxxx";
        insertPending(raw, Instant.now().minusSeconds(3600));
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_EXPIRED));
        awaitAuditReason("expired");
    }

    @Test
    void guardE_revoked() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        JsonNode created = mapper.readTree(listInvitations());
        UUID id = UUID.fromString(created.get(0).get("id").asText());
        mockMvc.perform(post("/api/v1/admin/invitations/" + id + "/revoke")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
        awaitAuditReason("revoked");
    }

    @Test
    void guardE_alreadyAccepted() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_ALREADY_USED));
        awaitAuditReason("already_accepted");
    }

    @Test
    void guardE_tokenInvalid() throws Exception {
        sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"this-is-not-a-valid-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_REJECTED));
        awaitAuditReason("token_invalid");
    }

    @Test
    void guardF_existingAccount() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        UUID existing = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status) VALUES (?, ?, ?, 'active')
                """, existing, INVITE_EMAIL, "Déjà là");
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.IDENTITY_CONFLICT));
        awaitAuditReason("identity_conflict");
    }

    @Test
    void guardG_licenceFull() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        doThrow(new AccessPolicyDeniedException(
                AccessDeniedReason.LICENCE_USER_LIMIT,
                "Aucune licence valide installée : contactez l'administrateur"))
                .when(licenceService).assertCanCreateUser(any());
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.LICENCE_USER_LIMIT));
        awaitAuditReason("licence_user_limit");
    }

    @Test
    void resend_invalidatesOldToken() throws Exception {
        String first = sendInvite(INVITE_EMAIL);
        JsonNode created = mapper.readTree(listInvitations());
        UUID id = UUID.fromString(created.get(0).get("id").asText());
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception ignored) {
            // mailbox vide
        }
        mockMvc.perform(post("/api/v1/admin/invitations/" + id + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk());
        String second = lastTokenFromMail();
        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + second + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentAccept_onlyOneSucceeds() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> acceptStatus(token, start));
            Future<Integer> b = pool.submit(() -> acceptStatus(token, start));
            start.countDown();
            int s1 = a.get(15, TimeUnit.SECONDS);
            int s2 = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(s1, s2)).contains(200);
            assertThat(s1 == 200 && s2 == 200).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE email = ?", Long.class, INVITE_EMAIL)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logsNeverContainTokenOrSmtpSecret() throws Exception {
        String token = sendInvite(INVITE_EMAIL);
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());
        String dump = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(dump).doesNotContain(token);
        assertThat(dump).doesNotContain(SMTP_PASSWORD);
        assertThat(dump).doesNotContain(PEPPER);
    }

    @Test
    void existingEmail_create409() throws Exception {
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status) VALUES (?, ?, ?, 'active')
                """, UUID.randomUUID(), INVITE_EMAIL, "Existe");
        mockMvc.perform(post("/api/v1/admin/invitations")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + INVITE_EMAIL + "\",\"globalRole\":\"CONTRIBUTEUR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.INVITATION_EMAIL_EXISTS));
    }

    private int acceptStatus(String token, CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            MvcResult r = mockMvc.perform(post("/api/v1/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, inviteeBearer(true))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + token + "\"}"))
                    .andReturn();
            return r.getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sendInvite(String email) throws Exception {
        mockMvc.perform(post("/api/v1/admin/invitations")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"teamId\":\"" + TEAM_ID
                                + "\",\"teamRole\":\"contributeur\",\"globalRole\":\"CONTRIBUTEUR\"}"))
                .andExpect(status().isCreated());
        return lastTokenFromMail();
    }

    private String lastTokenFromMail() throws Exception {
        for (int i = 0; i < 40; i++) {
            MimeMessage[] msgs = greenMail.getReceivedMessages();
            if (msgs.length > 0) {
                String body = decodedMail(msgs[msgs.length - 1]);
                Matcher m = Pattern.compile("/invitations/accept\\?token=([A-Za-z0-9_-]+)")
                        .matcher(body);
                if (m.find()) {
                    return m.group(1);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("aucun e-mail d'invitation");
    }

    /** Décodé (quoted-printable) : {@code token=3D…} ne doit pas être lu comme jeton. */
    private static String decodedMail(MimeMessage msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof jakarta.mail.Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                Object part = mp.getBodyPart(i).getContent();
                if (part instanceof String s) {
                    sb.append(s).append('\n');
                }
            }
            return sb.toString();
        }
        if (content instanceof String s) {
            return s;
        }
        return GreenMailUtil.getBody(msg);
    }

    private String listInvitations() throws Exception {
        return mockMvc.perform(get("/api/v1/admin/invitations")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void insertPending(String rawToken, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO invitations (
                    id, email, team_id, team_role, global_role, token_hash, status,
                    expires_at, invited_by, created_at, last_sent_at, send_count)
                VALUES (?, ?, ?, 'contributeur', 'CONTRIBUTEUR', ?, 'pending', ?, ?, now(), now(), 1)
                """,
                UUID.randomUUID(), INVITE_EMAIL, TEAM_ID, hasher.hash(rawToken),
                java.sql.Timestamp.from(expiresAt), ADMIN_ID);
    }

    private void seedAdmin() {
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, last_login_at)
                VALUES (?, 'admin@example.com', 'Admin', 'active', now())
                """, ADMIN_ID);
        jdbc.update("""
                INSERT INTO user_identities (user_id, issuer, subject, linked_at)
                VALUES (?, ?, ?, now())
                """, ADMIN_ID, ISSUER, ADMIN_SUB);
        jdbc.update("""
                INSERT INTO user_platform_roles (user_id, role, granted_at)
                VALUES (?, 'ADMINISTRATEUR_SYSTEME', now())
                """, ADMIN_ID);
    }

    private String createPat() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/me/tokens")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"it\",\"scope\":\"read_write\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).path("plaintext").asText();
    }

    private void awaitAudit(String action) throws Exception {
        for (int i = 0; i < 40; i++) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log_events WHERE action = ?", Long.class, action);
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("audit absent : " + action);
    }

    private void awaitAuditReason(String reason) throws Exception {
        for (int i = 0; i < 40; i++) {
            List<String> rows = jdbc.queryForList(
                    "SELECT metadata::text FROM audit_log_events WHERE action = ?",
                    String.class, AuditActions.INVITATION_ACCEPT_DENIED);
            if (rows.stream().anyMatch(m -> m.contains(reason))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("audit reason absent : " + reason);
    }

    private void assertDeniedReason(String reason) throws Exception {
        // PAT est filtré avant le service : pas d'audit invitation.accept_denied obligatoire.
        assertThat(reason).isEqualTo("pat");
    }

    private static String adminBearer() throws Exception {
        return bearer(ISSUER, ADMIN_SUB, "admin@example.com", true);
    }

    private static String inviteeBearer(boolean verified) throws Exception {
        return bearer(ISSUER, INVITEE_SUB, INVITE_EMAIL, verified);
    }

    private static String bearer(String issuer, String sub, String email, boolean verified) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", email)
                .claim("email_verified", verified)
                .claim("name", "Invité")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return "Bearer " + jwt.serialize();
    }
}
