// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.CreateStoreResponse;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Intégration {@link AuthorizationService} contre un vrai OpenFGA (Testcontainers, mémoire).
 * Image alignée sur {@code infra/docker-compose.yml} — voir {@code docs/openfga-versions.md}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorizationServiceOpenFgaTest {

    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";

    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SPACE_EDITOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID SPACE_VIEWER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID OUTSIDER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID GROUP_MEMBER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID ANYBODY = UUID.fromString("88888888-8888-8888-8888-888888888888");
    static final UUID STRANGER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> openfga = new GenericContainer<>(DockerImageName.parse(OPENFGA_IMAGE))
            .withCommand("run", "--datastore-engine", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));

    static OpenFgaClient client;
    static final ObjectMapper MAPPER = new ObjectMapper();

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
    static void bootStoreAndModel() throws Exception {
        String apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        client = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));

        CreateStoreResponse store = client.createStore(new CreateStoreRequest().name("socle-it")).get();
        client.setStoreId(store.getId());

        JsonNode root = MAPPER.readTree(Files.readString(resolveModelJson()));
        WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                .schemaVersion(root.get("schema_version").asText());
        List<TypeDefinition> defs = new ArrayList<>();
        for (JsonNode td : root.get("type_definitions")) {
            defs.add(MAPPER.treeToValue(td, TypeDefinition.class));
        }
        writeReq.setTypeDefinitions(defs);
        var modelResp = client.writeAuthorizationModel(writeReq).get();
        client.setAuthorizationModelId(modelResp.getAuthorizationModelId());
    }

    @BeforeEach
    void setUpService() {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga(null, null, null, 3, false, 2, 2, 500),
                null,
                null,
                null);
        authz = new AuthorizationService(client, jdbc, props);
    }

    @Test
    void provision_organisation_anyUserIsViewerNotEditor() {
        UUID space = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "organisation");

        assertThat(authz.hasRelation(ANYBODY, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(ANYBODY, "document", doc, "editor")).isFalse();
        assertThat(authz.hasRelation(CREATOR, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(CREATOR, "document", doc, "owner")).isFalse();
    }

    @Test
    void provision_space_spaceViewerIsDocumentViewer_outsiderIsNot() {
        UUID space = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "space");

        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "viewer")).isFalse();
    }

    @Test
    void provision_restricted_spaceEditorNotViewer_spaceOwnerIsOwner() {
        UUID space = UUID.randomUUID();
        authz.grantPermission("space", space, "owner", "user", SPACE_OWNER);
        authz.grantPermission("space", space, "editor", "user", SPACE_EDITOR);

        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "restricted");

        assertThat(authz.hasRelation(SPACE_EDITOR, "document", doc, "viewer")).isFalse();
        assertThat(authz.hasRelation(SPACE_OWNER, "document", doc, "owner")).isTrue();
        assertThat(authz.hasRelation(SPACE_OWNER, "document", doc, "editor")).isTrue();
        assertThat(authz.hasRelation(SPACE_OWNER, "document", doc, "viewer")).isTrue();
    }

    @Test
    void directAccessAlone_doesNotGrantViewer() {
        UUID space = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        authz.linkDocumentToSpace(doc, space, "restricted");
        authz.ensureDirectAccess(doc, "user:" + STRANGER);

        assertThat(authz.hasRelation(STRANGER, "document", doc, "viewer")).isFalse();
        assertThat(authz.listDirectAccessDocumentIds(STRANGER)).contains(doc);
    }

    @Test
    void groupMember_thenRevoke_losesAccess() {
        UUID space = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        authz.grantPermission("space", space, "editor", "group", group);
        authz.grantPermission("group", group, "member", "user", GROUP_MEMBER);

        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "space");

        assertThat(authz.hasRelation(GROUP_MEMBER, "document", doc, "editor")).isTrue();

        authz.revokePermission("group", group, "member", "user", GROUP_MEMBER);
        assertThat(authz.hasRelation(GROUP_MEMBER, "document", doc, "editor")).isFalse();
        assertThat(authz.hasRelation(GROUP_MEMBER, "document", doc, "viewer")).isFalse();
    }

    @Test
    void folder_inheritanceAndFolderOnlyGrant() {
        UUID space = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.linkFolderToParent(folder, "space", space);

        UUID folderGrantee = UUID.randomUUID();
        authz.grantPermission("folder", folder, "viewer", "user", folderGrantee);

        UUID doc = UUID.randomUUID();
        authz.linkDocumentToFolder(doc, folder, "space");

        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(folderGrantee, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "viewer")).isFalse();
    }

    @Test
    void folder_nestedInheritance_spaceFolderSubfolderDocument() {
        UUID space = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.provisionFolderAccess(folder, "space", space);
        authz.provisionFolderAccess(sub, "folder", folder);

        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, sub, CREATOR, "space");

        assertThat(authz.hasRelation(SPACE_VIEWER, "folder", sub, "viewer")).isTrue();
        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "viewer")).isFalse();
    }

    @Test
    void reparentFolder_atomic_oldParentNoLongerGrantsAccess() {
        UUID space = UUID.randomUUID();
        UUID folderA = UUID.randomUUID();
        UUID folderB = UUID.randomUUID();
        UUID doc = UUID.randomUUID();

        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.provisionFolderAccess(folderA, "space", space);
        authz.provisionFolderAccess(folderB, "space", space);
        authz.provisionDocumentAccess(doc, space, folderA, CREATOR, "space");

        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();

        // Retirer viewer espace, n'accorder viewer que sur folderA
        authz.revokePermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.grantPermission("folder", folderA, "viewer", "user", SPACE_VIEWER);
        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();

        authz.reparentDocument(doc, "folder:" + folderA, "folder:" + folderB, "space");
        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isFalse();

        authz.grantPermission("folder", folderB, "viewer", "user", SPACE_VIEWER);
        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isTrue();
    }

    @Test
    void restrictedInVisibleFolder_spaceMemberNotViewer() {
        UUID space = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.grantPermission("space", space, "editor", "user", SPACE_EDITOR);
        authz.provisionFolderAccess(folder, "space", space);

        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, folder, CREATOR, "restricted");

        assertThat(authz.hasRelation(SPACE_VIEWER, "folder", folder, "viewer")).isTrue();
        assertThat(authz.hasRelation(SPACE_VIEWER, "document", doc, "viewer")).isFalse();
        assertThat(authz.hasRelation(SPACE_EDITOR, "document", doc, "viewer")).isFalse();
        assertThat(authz.hasRelation(CREATOR, "document", doc, "viewer")).isTrue();
    }

    @Test
    void visibilityChange_atomicWrite_spaceToRestricted() {
        UUID space = UUID.randomUUID();
        authz.grantPermission("space", space, "editor", "user", SPACE_EDITOR);
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "space");
        assertThat(authz.hasRelation(SPACE_EDITOR, "document", doc, "viewer")).isTrue();

        authz.applyVisibilityTuples(doc, "space:" + space, "space", "restricted");
        assertThat(authz.hasRelation(SPACE_EDITOR, "document", doc, "viewer")).isFalse();
    }

    @Test
    void grantRevoke_documentEditor_togglesAccess() {
        UUID space = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "restricted");
        authz.grantPermission("document", doc, "editor", "user", OUTSIDER);
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "editor")).isTrue();
        authz.revokePermission("document", doc, "editor", "user", OUTSIDER);
        assertThat(authz.hasRelation(OUTSIDER, "document", doc, "editor")).isFalse();
    }

    @Test
    void readableScope_listObjects_S_view_S_owner_D_direct_F_view() {
        UUID space = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        authz.grantPermission("space", space, "owner", "user", SPACE_OWNER);
        authz.linkFolderToParent(folder, "space", space);

        UUID doc = UUID.randomUUID();
        authz.provisionDocumentAccess(doc, space, CREATOR, "space");

        assertThat(authz.readableScope(SPACE_VIEWER).spaceViewerIds()).contains(space);
        assertThat(authz.readableScope(SPACE_OWNER).spaceOwnerIds()).contains(space);
        assertThat(authz.readableScope(CREATOR).directDocumentIds()).contains(doc);

        authz.grantPermission("folder", folder, "viewer", "user", OUTSIDER);
        assertThat(authz.readableScope(OUTSIDER).folderViewerIds()).contains(folder);
    }

    @Test
    void filterByDocumentViewer_batchCheckChunked() {
        UUID space = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        List<UUID> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID doc = UUID.randomUUID();
            authz.provisionDocumentAccess(doc, space, CREATOR, "space");
            docs.add(doc);
        }
        UUID denied = UUID.randomUUID();
        authz.provisionDocumentAccess(denied, space, CREATOR, "restricted");
        docs.add(denied);

        List<UUID> allowed = authz.filterByDocumentViewer(SPACE_VIEWER, docs);
        assertThat(allowed).hasSize(5).doesNotContain(denied);
    }

    @Test
    void listObjectsCeiling_warnsWhenAtConfiguredMax(CapturedOutput output) {
        UUID space = UUID.randomUUID();
        authz.grantPermission("space", space, "viewer", "user", SPACE_VIEWER);
        for (int i = 0; i < 4; i++) {
            authz.provisionDocumentAccess(UUID.randomUUID(), space, CREATOR, "space");
        }
        List<UUID> ids = authz.listObjectsDocumentIds(SPACE_VIEWER);
        assertThat(ids.size()).isGreaterThanOrEqualTo(3);
        assertThat(output.getOut() + output.getErr()).contains("plafond");
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
        throw new IllegalStateException("model.json introuvable depuis " + System.getProperty("user.dir"));
    }
}
