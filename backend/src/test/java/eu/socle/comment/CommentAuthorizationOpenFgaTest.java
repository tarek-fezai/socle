// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.comment.CommentDtos.CreateCommentRequest;
import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.notification.NotificationService;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Contrôles commentaire contre OpenFGA réel + Postgres (policy members / all_readers).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentAuthorizationOpenFgaTest {

    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";
    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MEMBER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID STRANGER = UUID.fromString("99999999-9999-9999-9999-999999999999");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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

    static OpenFgaClient client;
    static JdbcTemplate jdbc;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentStore documentStore;
    @Mock ReliabilityScoreService reliabilityScoreService;

    AuthorizationService authz;
    CommentService comments;

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
        var store = client.createStore(new CreateStoreRequest().name("comments-it")).get();
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

        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("CREATE TABLE users (id UUID PRIMARY KEY, email TEXT, display_name TEXT, status TEXT)");
        jdbc.execute("""
                CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT, comment_policy TEXT DEFAULT 'members',
                  deleted_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE documents (id UUID PRIMARY KEY, space_id UUID, title TEXT, body JSONB DEFAULT '{}',
                  current_version_no INT DEFAULT 1, deleted_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE document_comments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL, parent_comment_id UUID, author_id UUID NOT NULL,
                  body TEXT NOT NULL, anchor_block_id TEXT, resolved BOOLEAN DEFAULT false,
                  resolved_by UUID, resolved_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now(),
                  updated_at TIMESTAMPTZ DEFAULT now(), deleted_at TIMESTAMPTZ, deleted_by UUID,
                  deleted_by_moderator BOOLEAN DEFAULT false, author_display_name TEXT,
                  author_anonymized BOOLEAN DEFAULT false, status TEXT DEFAULT 'ouvert',
                  anchor_exact TEXT, anchor_prefix TEXT, anchor_suffix TEXT, anchor_version_no INT
                )
                """);
        for (UUID u : List.of(CREATOR, MEMBER, STRANGER)) {
            jdbc.update("INSERT INTO users VALUES (?,?,?,?)", u, u + "@ex.com", "U", "active");
        }
        jdbc.update("INSERT INTO spaces VALUES (?,?, 'members', NULL)", SPACE, "S");
        jdbc.update("INSERT INTO documents VALUES (?,?, 'T', '{}'::jsonb, 1, NULL)", DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_comments");
        jdbc.update("UPDATE spaces SET comment_policy = 'members' WHERE id = ?", SPACE);
        SocleProperties props = new SocleProperties(
                null, new SocleProperties.OpenFga(null, null, null, 100, false, 2, 2, 500),
                null, null, null);
        authz = new AuthorizationService(client, jdbc, props);
        comments = new CommentService(
                jdbc, userSyncService, authz, auditService, notificationService,
                documentRepository, documentStore, reliabilityScoreService);
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC);
        doc.setSpaceId(SPACE);
        doc.setBody(Map.of());
        doc.setCurrentVersionNo(1);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(doc));
        when(documentStore.readCurrentContent(any(), any())).thenReturn(Map.of("type", "doc", "content", List.of()));
        doNothing().when(reliabilityScoreService).onDocumentCommentChanged(any());
        doNothing().when(auditService).record(any(), any(Boolean.class), any(), any(), any(), any(), any());

        // FGA : doc organisation → stranger viewer ; member = space viewer
        authz.provisionDocumentAccess(DOC, SPACE, CREATOR, "organisation");
        authz.grantPermission("space", SPACE, "viewer", "user", MEMBER);
    }

    @Test
    void stranger_readsOrgDoc_cannotCommentWhenMembers_canWhenAllReaders() {
        // lecture OK via user:*
        assertThat(authz.hasRelation(STRANGER, "document", DOC, "viewer")).isTrue();
        assertThat(authz.hasRelation(STRANGER, "space", SPACE, "viewer")).isFalse();

        assertThatThrownBy(() -> comments.create(jwt(STRANGER), DOC, new CreateCommentRequest("no", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        jdbc.update("UPDATE spaces SET comment_policy = 'all_readers' WHERE id = ?", SPACE);
        var c = comments.create(jwt(STRANGER), DOC, new CreateCommentRequest("yes", null, null));
        assertThat(c.body()).isEqualTo("yes");
    }

    @Test
    void spaceMember_canCommentUnderMembersPolicy() {
        var c = comments.create(jwt(MEMBER), DOC, new CreateCommentRequest("from member", null, null));
        assertThat(c.authorId()).isEqualTo(MEMBER);
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("e");
        u.setDisplayName("n");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t").header("alg", "none").subject(sub.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }

    private static Path resolveModelJson() {
        Path fromModule = Path.of(System.getProperty("user.dir"))
                .resolve("../infra/openfga/model.json").normalize();
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
