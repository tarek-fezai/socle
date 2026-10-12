// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * Migration {@code team-roles-v1} contre Postgres + OpenFGA réels.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamRolesTupleMigrationIT {

    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID CONTRIB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID READER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID GROUP = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID ORG = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> openfga = new GenericContainer<>(DockerImageName.parse(OPENFGA_IMAGE))
            .withCommand("run", "--datastore-engine", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core").withUsername("socle").withPassword("socle");

    static JdbcTemplate jdbc;
    static String apiUrl;
    static final Resource PRE = new ClassPathResource("openfga/model-pre-team-roles.json");
    static final Resource CUR = new ClassPathResource("openfga/model.json");

    OpenFgaClient client;
    AuthorizationService authz;
    TeamRolesTupleMigrationService migration;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT, display_name TEXT, status TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE groups (
                  id UUID PRIMARY KEY, name TEXT, created_by UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL, user_id UUID NOT NULL, role TEXT NOT NULL,
                  PRIMARY KEY (group_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE authz_migrations (
                  name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), report JSONB
                )
                """);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?),(?,?,?,?),(?,?,?,?)",
                CREATOR, "c@ex.com", "Créateur", "active",
                CONTRIB, "k@ex.com", "Contributeur", "active",
                READER, "l@ex.com", "Lecteur", "active");
        jdbc.update("INSERT INTO groups (id, name, created_by) VALUES (?,?,?)", GROUP, "Équipe", CREATOR);
        jdbc.update(
                "INSERT INTO group_members (group_id, user_id, role) VALUES (?,?,?),(?,?,?),(?,?,?)",
                GROUP, CREATOR, "proprietaire",
                GROUP, CONTRIB, "contributeur",
                GROUP, READER, "lecteur");
    }

    @BeforeEach
    void storeAndLegacyTuples() throws Exception {
        jdbc.update("DELETE FROM authz_migrations");
        client = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));
        var store = client.createStore(new CreateStoreRequest().name("team-roles-v1-it")).get();
        client.setStoreId(store.getId());
        writeModel(PRE);

        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga(null, null, null, 3, false, 2, 2, 500),
                null, null, null);
        authz = new AuthorizationService(client, jdbc, props);
        migration = new TeamRolesTupleMigrationService(authz, jdbc, MAPPER, client, PRE, CUR);

        String gMember = "group:" + GROUP + "#member";
        client.write(new ClientWriteRequest().writes(List.of(
                tuple("user:" + CREATOR, "member", "group:" + GROUP),
                tuple("user:" + CONTRIB, "member", "group:" + GROUP),
                tuple("user:" + READER, "member", "group:" + GROUP),
                tuple(gMember, "owner", "space:" + SPACE),
                tuple(gMember, "viewer", "document:" + DOC),
                tuple(gMember, "editor", "organisation:" + ORG)
        ))).join();
    }

    @Test
    void migrateAtStartup_rewritesLegacy_accessMatchesMatrix_zeroLeftover() {
        int before = countLegacy();
        System.out.println("team-roles-v1 AVANT leftover=" + before);
        assertThat(before).isEqualTo(6);

        TeamRolesTupleMigrationService.MigrationReport report = migration.migrateAtStartup();

        int after = countLegacy();
        System.out.println("team-roles-v1 APRÈS leftover=" + after
                + " membersRewritten=" + report.membersRewritten()
                + " grantsRewritten=" + report.grantsRewritten()
                + " errors=" + report.errors());
        assertThat(after).isZero();
        assertThat(report.errors()).isZero();
        assertThat(report.leftoverLegacy()).isZero();
        assertThat(report.skipped()).isFalse();

        Integer marked = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                TeamRolesTupleMigrationService.MIGRATION_NAME);
        assertThat(marked).isEqualTo(1);

        assertThat(authz.hasRelation(CREATOR, "space", SPACE, "owner")).isTrue();
        assertThat(authz.hasRelation(CONTRIB, "space", SPACE, "owner")).isFalse();
        assertThat(authz.hasRelation(READER, "space", SPACE, "owner")).isFalse();
        assertThat(authz.hasRelation(CONTRIB, "space", SPACE, "editor")).isTrue();
        assertThat(authz.hasRelation(READER, "space", SPACE, "editor")).isFalse();
        assertThat(authz.hasRelation(READER, "space", SPACE, "viewer")).isTrue();

        assertThat(authz.hasRelation(CREATOR, "document", DOC, "viewer")).isTrue();
        assertThat(authz.hasRelation(CONTRIB, "document", DOC, "viewer")).isTrue();
        assertThat(authz.hasRelation(READER, "document", DOC, "viewer")).isTrue();
        assertThat(authz.hasRelation(READER, "document", DOC, "editor")).isFalse();

        assertThat(authz.hasRelation(CONTRIB, "organisation", ORG, "editor")).isTrue();
        assertThat(authz.hasRelation(READER, "organisation", ORG, "editor")).isFalse();
        assertThat(authz.hasRelation(READER, "organisation", ORG, "viewer")).isTrue();

        TeamRolesTupleMigrationService.MigrationReport second = migration.migrateAtStartup();
        assertThat(second.skipped()).isTrue();
        assertThat(countLegacy()).isZero();
    }

    @Test
    void migrateAtStartup_fgaError_notMarked_startupRefused() {
        AuthorizationService spyAuthz = spy(authz);
        doThrow(new RuntimeException("injected FGA error"))
                .when(spyAuthz)
                .rewriteLegacyGroupMemberGrantOn(anyString(), anyString(), anyString(), any(UUID.class));
        TeamRolesTupleMigrationService failing = new TeamRolesTupleMigrationService(
                spyAuthz, jdbc, MAPPER, client, PRE, CUR);

        assertThatThrownBy(failing::migrateAtStartup)
                .isInstanceOf(TeamRolesTupleMigrationService.TeamRolesMigrationFailedException.class)
                .hasMessageContaining("erreur")
                .hasMessageContaining("Non marquée appliquée");

        Integer marked = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                TeamRolesTupleMigrationService.MIGRATION_NAME);
        assertThat(marked).isZero();
        assertThat(countLegacy()).isPositive();
    }

    private int countLegacy() {
        int n = 0;
        for (AuthorizationService.AccessTuple t : authz.readAllTuplesPaged()) {
            String obj = t.object();
            if (obj != null && (obj.startsWith("organisation:")
                    || obj.startsWith("space:")
                    || obj.startsWith("folder:")
                    || obj.startsWith("document:"))
                    && TeamRolesTupleMigrationService.parseGroupMemberSubject(t.user()) != null) {
                n++;
            } else if (obj != null && obj.startsWith("group:")
                    && "member".equals(t.relation())
                    && t.user() != null && t.user().startsWith("user:")) {
                n++;
            }
        }
        return n;
    }

    private void writeModel(Resource resource) throws Exception {
        JsonNode root = MAPPER.readTree(resource.getInputStream());
        WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                .schemaVersion(root.get("schema_version").asText());
        List<TypeDefinition> defs = new ArrayList<>();
        for (JsonNode td : root.get("type_definitions")) {
            defs.add(MAPPER.treeToValue(td, TypeDefinition.class));
        }
        writeReq.setTypeDefinitions(defs);
        client.setAuthorizationModelId(
                client.writeAuthorizationModel(writeReq).get().getAuthorizationModelId());
    }

    private static ClientTupleKey tuple(String user, String relation, String object) {
        return new ClientTupleKey().user(user).relation(relation)._object(object);
    }
}
