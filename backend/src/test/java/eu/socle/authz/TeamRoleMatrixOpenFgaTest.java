// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import eu.socle.config.SocleProperties;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matrice docs/teams-and-access.md § 1.3 — OpenFGA réel, cas positifs et négatifs.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamRoleMatrixOpenFgaTest {

    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID APPROVER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CONTRIB = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID READER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID OUTSIDER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> openfga = new GenericContainer<>(DockerImageName.parse(OPENFGA_IMAGE))
            .withCommand("run", "--datastore-engine", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));

    static OpenFgaClient client;

    @Mock JdbcTemplate jdbc;
    AuthorizationService authz;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        String apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        client = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));
        var store = client.createStore(new CreateStoreRequest().name("team-roles")).get();
        client.setStoreId(store.getId());
        JsonNode root = MAPPER.readTree(Files.readString(resolveModelJson()));
        WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                .schemaVersion(root.get("schema_version").asText());
        List<TypeDefinition> defs = new ArrayList<>();
        for (JsonNode td : root.get("type_definitions")) {
            defs.add(MAPPER.treeToValue(td, TypeDefinition.class));
        }
        writeReq.setTypeDefinitions(defs);
        client.setAuthorizationModelId(client.writeAuthorizationModel(writeReq).get().getAuthorizationModelId());
    }

    @BeforeEach
    void setUp() {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga(null, null, null, 3, false, 2, 2, 500),
                null, null, null);
        authz = new AuthorizationService(client, jdbc, props);
    }

    @Test
    void grantOwner_proprietaireIsOwner_othersAreNot() {
        UUID group = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        seedRoles(group);
        authz.grantPermission("space", space, "owner", "group", group);
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, UUID.randomUUID(), "space");

        assertThat(authz.hasRelation(OWNER, "document", doc, "owner")).isTrue();
        assertThat(authz.hasRelation(OWNER, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(APPROVER, "document", doc, "owner")).isFalse();
        assertThat(authz.hasRelation(APPROVER, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(CONTRIB, "document", doc, "owner")).isFalse();
        assertThat(authz.hasRelation(CONTRIB, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(READER, "document", doc, "editor")).isFalse();
        assertThat(authz.hasRelation(READER, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "viewer")).isFalse();
    }

    @Test
    void grantEditor_lecteurIsViewerOnly() {
        UUID group = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        seedRoles(group);
        authz.grantPermission("space", space, "editor", "group", group);
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, UUID.randomUUID(), "space");

        assertThat(authz.hasRelation(OWNER, "document", doc, "owner")).isFalse();
        assertThat(authz.hasRelation(OWNER, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(APPROVER, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(CONTRIB, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(READER, "document", doc, "editor")).isFalse();
        assertThat(authz.hasRelation(READER, "document", doc, "viewer")).isTrue();
    }

    @Test
    void grantViewer_nobodyIsEditor() {
        UUID group = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        seedRoles(group);
        authz.grantPermission("space", space, "viewer", "group", group);
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, UUID.randomUUID(), "space");

        for (UUID u : List.of(OWNER, APPROVER, CONTRIB, READER)) {
            assertThat(authz.hasRelation(u, "document", doc, "owner")).isFalse();
            assertThat(authz.hasRelation(u, "document", doc, "editor")).isFalse();
            assertThat(authz.hasRelation(u, "document", doc, "viewer")).isTrue();
        }
    }

    private void seedRoles(UUID group) {
        authz.grantPermission("group", group, "proprietaire", "user", OWNER);
        authz.grantPermission("group", group, "approbateur", "user", APPROVER);
        authz.grantPermission("group", group, "contributeur", "user", CONTRIB);
        authz.grantPermission("group", group, "lecteur", "user", READER);
    }

    private static Path resolveModelJson() {
        Path fromModule = Path.of(System.getProperty("user.dir"))
                .resolve("../infra/openfga/model.json")
                .normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        Path local = Path.of("infra/openfga/model.json");
        if (Files.isRegularFile(local)) {
            return local.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("model.json introuvable");
    }
}
