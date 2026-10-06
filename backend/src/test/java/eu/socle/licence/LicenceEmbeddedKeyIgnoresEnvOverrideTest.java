// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.identity.IdentityProperties;
import eu.socle.testsupport.MigratedPostgres;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Constructor;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Point 2 : aucune surcharge runtime de la clé publique.
 * Même si {@code SOCLE_LICENCE_PUBLIC_KEY_B64} / {@code socle.licence.public-key-b64}
 * sont définis dans l'environnement, la clé embarquée (classpath) reste utilisée.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LicenceEmbeddedKeyIgnoresEnvOverrideTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;
    static ObjectMapper mapper = new ObjectMapper();
    static final UUID ADMIN = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    IdentityProperties identityProperties;
    LicenceService service;
    Jwt jwt;
    /** Paire étrangère — simule une clé « d'environnement » différente de l'embarquée. */
    KeyPair foreignKeyPair;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'admin2@test', 'Admin2', 'active')",
                ADMIN);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM instance_licence");
        identityProperties = new IdentityProperties();
        identityProperties.setBootstrapAdminSubjects(List.of());

        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(ADMIN.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();

        foreignKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String foreignPubB64 = Base64.getEncoder().encodeToString(foreignKeyPair.getPublic().getEncoded());
        // Simule la présence d'un override runtime (ne doit plus être lu par LicenceService).
        System.setProperty("socle.licence.public-key-b64", foreignPubB64);
        System.setProperty("SOCLE_LICENCE_PUBLIC_KEY_B64", foreignPubB64);

        // Constructeur production : classpath uniquement (ed25519-public.b64 du dépôt / image).
        service = new LicenceService(
                jdbc, identityFacade, identityProperties, auditService, mapper, CLOCK,
                new ClassPathResource("licence/ed25519-public.b64"));
    }

    @AfterEach
    void clearSimulatedEnvOverride() {
        System.clearProperty("socle.licence.public-key-b64");
        System.clearProperty("SOCLE_LICENCE_PUBLIC_KEY_B64");
    }

    @Test
    void noRuntimeStringOverrideOnProductionConstructor() {
        boolean hasStringAndResource = Arrays.stream(LicenceService.class.getDeclaredConstructors())
                .anyMatch(LicenceEmbeddedKeyIgnoresEnvOverrideTest::takesStringAndResource);
        assertThat(hasStringAndResource)
                .as("pas de @Value socle.licence.public-key-b64 sur le constructeur")
                .isFalse();
    }

    @Test
    void licenceSignedWithEnvKeyRejected_embeddedClasspathKeyUsed() throws Exception {
        String foreignSigned = signedWith(foreignKeyPair, "LIC-FOREIGN", "Other", "E",
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 5);

        assertThatThrownBy(() -> service.importLicence(jwt,
                new LicenceDtos.ImportLicenceRequest(foreignSigned)))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.LICENCE_REJECTED);
                    assertThat(c.getReason()).containsIgnoringCase("signature");
                });
    }

    private static boolean takesStringAndResource(Constructor<?> c) {
        List<Class<?>> types = Arrays.asList(c.getParameterTypes());
        return types.contains(String.class)
                && types.stream().anyMatch(t -> org.springframework.core.io.Resource.class.isAssignableFrom(t));
    }

    private String signedWith(
            KeyPair kp, String id, String licensee, String edition,
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
        s.initSign(kp.getPrivate());
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
}
