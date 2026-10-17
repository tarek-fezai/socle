// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.CreateStoreResponse;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentService;
import eu.socle.folder.FolderDtos.FolderView;
import eu.socle.folder.FolderDtos.SpaceTreeResponse;
import eu.socle.folder.FolderDtos.TreeFolderNode;
import eu.socle.folder.FolderService;
import eu.socle.identity.IdentityFacade;
import eu.socle.space.SpaceDtos.SpaceDetail;
import eu.socle.space.SpaceDtos.SpaceView;
import eu.socle.trash.TrashService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lecteur d'un dossier sans membership espace (issue 82), contre Postgres et OpenFGA réels.
 * Le modèle d'autorisation n'est pas modifié.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderViewerReachOpenFgaTest {

    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID GUEST = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    static final UUID SPACE_EMPTY = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    static final UUID P = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    static final UUID F_EMPTY = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    static final UUID G = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    static final UUID HIDDEN_EMPTY = UUID.fromString("f1000000-0000-4000-8000-000000000004");
    static final UUID PAGE_EMPTY = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    static final UUID SECRET_EMPTY = UUID.fromString("d1000000-0000-4000-8000-000000000002");
    static final UUID HIDDEN_DOC_EMPTY = UUID.fromString("d1000000-0000-4000-8000-000000000003");

    static final UUID SPACE_DOCS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    static final UUID F_DOCS = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    static final UUID HIDDEN_DOCS = UUID.fromString("f2000000-0000-4000-8000-000000000002");
    static final UUID DOC_OK = UUID.fromString("d2000000-0000-4000-8000-000000000001");
    static final UUID DOC_SECRET = UUID.fromString("d2000000-0000-4000-8000-000000000002");
    static final UUID DOC_HIDDEN = UUID.fromString("d2000000-0000-4000-8000-000000000003");
    static final UUID PAGE_DOCS = UUID.fromString("d2000000-0000-4000-8000-000000000004");
    static final UUID TAG_OK = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    static final UUID TAG_SECRET = UUID.fromString("c2000000-0000-4000-8000-000000000002");

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
            .waitingFor(Wait.forHttp("/healthz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));

    static JdbcTemplate jdbc;
    static AuthorizationService authz;

    @Mock UserSyncService userSyncService;
    @Mock DocumentService documentService;

    SpaceService spaceService;
    FolderService folderService;
    SpaceHomeService homeService;
    SpacePageService pageService;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL,
                  avatar_initials TEXT, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, color TEXT, description TEXT,
                  external_reference TEXT NOT NULL DEFAULT 'open',
                  default_visibility TEXT NOT NULL DEFAULT 'organisation',
                  comment_policy TEXT NOT NULL DEFAULT 'members',
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ,
                  page_document_id UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE space_owners (
                  space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  is_responsible BOOLEAN NOT NULL DEFAULT false,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  PRIMARY KEY (space_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE folders (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL, parent_folder_id UUID,
                  name TEXT NOT NULL, position INT NOT NULL DEFAULT 0,
                  created_by UUID, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ,
                  status TEXT, owner_group_id UUID, page_document_id UUID, updated_by UUID,
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL,
                  folder_id UUID,
                  title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}'::jsonb,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  doc_type TEXT,
                  visibility TEXT NOT NULL DEFAULT 'space',
                  position INT NOT NULL DEFAULT 0,
                  language TEXT,
                  reliability_score NUMERIC,
                  updated_by UUID,
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE groups (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, created_at TIMESTAMPTZ, created_by UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL, user_id UUID NOT NULL, role TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE audit_log_events (
                  id UUID PRIMARY KEY, action TEXT, resource_type TEXT, resource_id UUID,
                  metadata JSONB, created_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY, document_id UUID, status TEXT, created_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, color TEXT, created_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL, tag_id UUID NOT NULL, PRIMARY KEY (document_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_role_assignments (
                  id UUID PRIMARY KEY, scope_type TEXT, scope_ref TEXT
                )
                """);

        String apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        OpenFgaClient fgaClient = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));
        CreateStoreResponse store = fgaClient.createStore(new CreateStoreRequest().name("folder-viewer-reach")).get();
        fgaClient.setStoreId(store.getId());
        JsonNode root = MAPPER.readTree(Files.readString(resolveModelJson()));
        WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                .schemaVersion(root.get("schema_version").asText());
        List<TypeDefinition> defs = new ArrayList<>();
        for (JsonNode td : root.get("type_definitions")) {
            defs.add(MAPPER.treeToValue(td, TypeDefinition.class));
        }
        writeReq.setTypeDefinitions(defs);
        var modelResp = fgaClient.writeAuthorizationModel(writeReq).get();
        fgaClient.setAuthorizationModelId(modelResp.getAuthorizationModelId());

        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga(null, null, null, 200, false, 50, 4, 500),
                null, null, null);
        authz = new AuthorizationService(fgaClient, jdbc, props);

        jdbc.update("""
                INSERT INTO users (id, email, display_name, avatar_initials, status)
                VALUES (?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)
                """,
                OWNER, "owner@x", "Owner One", "OO", "active",
                GUEST, "guest@x", "Guest Two", "GT", "active",
                STRANGER, "stranger@x", "No Access", "NA", "active");

        insertSpace(SPACE_EMPTY, "Espace vide", PAGE_EMPTY);
        insertSpace(SPACE_DOCS, "Espace documents", PAGE_DOCS);
        insertFolder(P, SPACE_EMPTY, null, "Parent", 0);
        insertFolder(F_EMPTY, SPACE_EMPTY, P, "F vide", 0);
        insertFolder(G, SPACE_EMPTY, F_EMPTY, "G hérité", 0);
        insertFolder(HIDDEN_EMPTY, SPACE_EMPTY, null, "Caché vide", 1);
        insertFolder(F_DOCS, SPACE_DOCS, null, "F documents", 0);
        insertFolder(HIDDEN_DOCS, SPACE_DOCS, null, "Caché documents", 1);

        insertDoc(PAGE_EMPTY, SPACE_EMPTY, null, "Page vide", "space", "valide", null);
        insertDoc(SECRET_EMPTY, SPACE_EMPTY, F_EMPTY, "Secret du dossier vide", "restricted", "brouillon", new BigDecimal("10"));
        insertDoc(HIDDEN_DOC_EMPTY, SPACE_EMPTY, HIDDEN_EMPTY, "Doc caché vide", "space", "en_revue", new BigDecimal("20"));
        insertDoc(PAGE_DOCS, SPACE_DOCS, null, "Page documents", "space", "valide", null);
        insertDoc(DOC_OK, SPACE_DOCS, F_DOCS, "Visible", "space", "valide", new BigDecimal("80"));
        insertDoc(DOC_SECRET, SPACE_DOCS, F_DOCS, "Restreint", "restricted", "brouillon", new BigDecimal("10"));
        insertDoc(DOC_HIDDEN, SPACE_DOCS, HIDDEN_DOCS, "Dans le frère", "space", "en_revue", new BigDecimal("30"));

        jdbc.update("INSERT INTO tags (id, name, color, created_at) VALUES (?,?,?,now()), (?,?,?,now())",
                TAG_OK, "Visible", "#111111", TAG_SECRET, "Secret", "#222222");
        jdbc.update("INSERT INTO document_tags (document_id, tag_id) VALUES (?,?),(?,?)",
                DOC_OK, TAG_OK, DOC_SECRET, TAG_SECRET);

        authz.grantPermission("space", SPACE_EMPTY, "owner", "user", OWNER);
        authz.grantPermission("space", SPACE_DOCS, "owner", "user", OWNER);
        authz.provisionFolderAccess(P, "space", SPACE_EMPTY);
        authz.provisionFolderAccess(F_EMPTY, "folder", P);
        authz.provisionFolderAccess(G, "folder", F_EMPTY);
        authz.provisionFolderAccess(HIDDEN_EMPTY, "space", SPACE_EMPTY);
        authz.provisionFolderAccess(F_DOCS, "space", SPACE_DOCS);
        authz.provisionFolderAccess(HIDDEN_DOCS, "space", SPACE_DOCS);
        authz.grantPermission("folder", F_EMPTY, "viewer", "user", GUEST);
        authz.grantPermission("folder", F_DOCS, "viewer", "user", GUEST);
        authz.provisionDocumentAccess(PAGE_EMPTY, SPACE_EMPTY, OWNER, "space");
        authz.provisionDocumentAccess(SECRET_EMPTY, SPACE_EMPTY, F_EMPTY, OWNER, "restricted");
        authz.provisionDocumentAccess(HIDDEN_DOC_EMPTY, SPACE_EMPTY, HIDDEN_EMPTY, OWNER, "space");
        authz.provisionDocumentAccess(PAGE_DOCS, SPACE_DOCS, OWNER, "space");
        authz.provisionDocumentAccess(DOC_OK, SPACE_DOCS, F_DOCS, OWNER, "space");
        authz.provisionDocumentAccess(DOC_SECRET, SPACE_DOCS, F_DOCS, OWNER, "restricted");
        authz.provisionDocumentAccess(DOC_HIDDEN, SPACE_DOCS, HIDDEN_DOCS, OWNER, "space");
    }

    @BeforeEach
    void setUp() {
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        IdentityFacade identity = mock(IdentityFacade.class);
        when(identity.isSystemAdmin(any())).thenReturn(false);
        AuditService audit = mock(AuditService.class);
        SpaceReachability reachability = new SpaceReachability(jdbc, authz);
        spaceService = new SpaceService(jdbc, userSyncService, authz, audit, identity, reachability);
        folderService = new FolderService(
                jdbc, userSyncService, authz, audit, mock(TrashService.class),
                new SocleProperties(null, null, null, null, new SocleProperties.Folders(5)),
                reachability);
        homeService = new SpaceHomeService(
                jdbc, userSyncService, authz, spaceService, reachability, folderService);
        pageService = new SpacePageService(jdbc, userSyncService, authz, documentService, reachability);
    }

    @Test
    void emptyFolder_spaceFolderAndTreeAreReachable() {
        SpaceView space = spaceService.get(jwt(GUEST), SPACE_EMPTY);
        assertThat(space.membership()).isEqualTo("public-only");
        assertThat(space.canManage()).isFalse();
        assertThat(space.isOwner()).isFalse();
        assertThat(space.documentCount()).isZero();

        FolderView folder = folderService.get(jwt(GUEST), F_EMPTY);
        assertThat(folder.name()).isEqualTo("F vide");
        assertThat(folder.documentCount()).isZero();
        assertThat(folder.documents()).extracting(d -> d.id()).doesNotContain(SECRET_EMPTY);
        assertThat(folder.canEdit()).isFalse();

        SpaceTreeResponse tree = folderService.tree(jwt(GUEST), SPACE_EMPTY, null);
        assertThat(treeIds(tree)).containsExactlyInAnyOrder(P, F_EMPTY, G);
        assertThat(tree.totalDocuments()).isZero();
        assertThat(tree.totalFolders()).isEqualTo(3);
    }

    @Test
    void inheritedSubfolder_isVisibleWithItsAncestor() {
        FolderView child = folderService.get(jwt(GUEST), G);
        assertThat(child.name()).isEqualTo("G hérité");
        assertThat(child.parentFolderId()).isEqualTo(F_EMPTY);

        SpaceTreeResponse tree = folderService.tree(jwt(GUEST), SPACE_EMPTY, null);
        TreeFolderNode parent = tree.folders().stream().filter(f -> P.equals(f.id())).findFirst().orElseThrow();
        TreeFolderNode empty = parent.folders().stream().filter(f -> F_EMPTY.equals(f.id())).findFirst().orElseThrow();
        assertThat(empty.folders()).extracting(TreeFolderNode::id).containsExactly(G);
    }

    @Test
    void folderWithDocuments_showsReadableDocsOnly() {
        SpaceView space = spaceService.get(jwt(GUEST), SPACE_DOCS);
        assertThat(space.membership()).isEqualTo("public-only");
        assertThat(space.documentCount()).isEqualTo(1);

        FolderView folder = folderService.get(jwt(GUEST), F_DOCS);
        assertThat(folder.documentCount()).isEqualTo(1);
        assertThat(folder.documents()).extracting(d -> d.id()).containsExactly(DOC_OK);

        SpaceTreeResponse tree = folderService.tree(jwt(GUEST), SPACE_DOCS, null);
        assertThat(treeIds(tree)).containsExactly(F_DOCS);
        assertThat(tree.totalDocuments()).isEqualTo(1);
        assertThat(tree.folders().getFirst().documents()).extracting(d -> d.id()).containsExactly(DOC_OK);
    }

    @Test
    void restrictedDocument_doesNotLeakThroughCounters() {
        SpaceDetail home = homeService.get(jwt(GUEST), SPACE_DOCS);
        assertThat(home.membership()).isEqualTo("public-only");
        assertThat(home.canEdit()).isFalse();
        assertThat(home.canDelete()).isFalse();
        assertThat(home.canManage()).isFalse();
        assertThat(home.isOwner()).isFalse();
        assertThat(home.pageDocumentId()).isNull();
        assertThat(home.documentCount()).isEqualTo(1);
        assertThat(home.folders()).extracting(f -> f.id()).containsExactly(F_DOCS);
        assertThat(home.folders().getFirst().documentCount()).isEqualTo(1);
        assertThat(home.rootDocuments()).isEmpty();
        assertThat(home.toReview()).isEmpty();
        assertThat(home.tags()).extracting(t -> t.id()).containsExactly(TAG_OK);
        assertThat(home.statusCounts().validated()).isEqualTo(1);
        assertThat(home.statusCounts().inReview()).isZero();
        assertThat(home.statusCounts().draft()).isZero();
        assertThat(home.reliabilityAverage()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(home.memberPreview()).isNotEmpty();
        assertThat(home.memberCount()).isEqualTo(1);
    }

    @Test
    void list_includesSpacesReachedByFolderViewer() {
        List<SpaceView> listed = spaceService.list(jwt(GUEST));
        assertThat(listed).extracting(SpaceView::id).contains(SPACE_EMPTY, SPACE_DOCS);
        assertThat(listed).allSatisfy(s -> {
            assertThat(s.membership()).isEqualTo("public-only");
            assertThat(s.canManage()).isFalse();
        });
        SpaceView empty = listed.stream().filter(s -> SPACE_EMPTY.equals(s.id())).findFirst().orElseThrow();
        SpaceView docs = listed.stream().filter(s -> SPACE_DOCS.equals(s.id())).findFirst().orElseThrow();
        assertThat(empty.documentCount()).isZero();
        assertThat(docs.documentCount()).isEqualTo(1);
    }

    @Test
    void homeOfEmptyFolder_listsOnlyTheVisibleAncestor() {
        SpaceDetail home = homeService.get(jwt(GUEST), SPACE_EMPTY);
        assertThat(home.membership()).isEqualTo("public-only");
        assertThat(home.canEdit()).isFalse();
        assertThat(home.canDelete()).isFalse();
        assertThat(home.pageDocumentId()).isNull();
        assertThat(home.folders()).extracting(f -> f.id()).containsExactly(P);
        assertThat(home.folders().getFirst().documentCount()).isZero();
        assertThat(home.rootDocuments()).isEmpty();
        assertThat(home.toReview()).isEmpty();
        assertThat(home.tags()).isEmpty();
        assertThat(home.statusCounts().validated()).isZero();
        assertThat(home.statusCounts().inReview()).isZero();
        assertThat(home.statusCounts().draft()).isZero();
        assertThat(home.reliabilityAverage()).isNull();
        assertThat(home.documentCount()).isZero();
    }

    @Test
    void page_folderViewerGets204_strangerGets404() {
        assertThat(pageService.get(jwt(GUEST), SPACE_EMPTY)).isNull();
        assertThat(pageService.get(jwt(GUEST), SPACE_DOCS)).isNull();
        verify(documentService, never()).get(any(), any());

        assertThatThrownBy(() -> pageService.get(jwt(STRANGER), SPACE_EMPTY))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> pageService.get(jwt(STRANGER), SPACE_DOCS))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void stranger_gets404OnSpaceTreeAndFolder() {
        assertNotFound(() -> spaceService.get(jwt(STRANGER), SPACE_EMPTY));
        assertNotFound(() -> spaceService.get(jwt(STRANGER), SPACE_DOCS));
        assertNotFound(() -> folderService.tree(jwt(STRANGER), SPACE_EMPTY, null));
        assertNotFound(() -> folderService.tree(jwt(STRANGER), SPACE_DOCS, null));
        assertNotFound(() -> folderService.get(jwt(STRANGER), F_EMPTY));
        assertNotFound(() -> folderService.get(jwt(STRANGER), F_DOCS));
        assertThat(spaceService.list(jwt(STRANGER))).extracting(SpaceView::id)
                .doesNotContain(SPACE_EMPTY, SPACE_DOCS);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static Set<UUID> treeIds(SpaceTreeResponse tree) {
        Set<UUID> ids = new HashSet<>();
        for (TreeFolderNode node : tree.folders()) {
            collect(node, ids);
        }
        return ids;
    }

    private static void collect(TreeFolderNode node, Set<UUID> ids) {
        ids.add(node.id());
        for (TreeFolderNode child : node.folders()) {
            collect(child, ids);
        }
    }

    private static void insertSpace(UUID id, String name, UUID pageId) {
        jdbc.update("""
                INSERT INTO spaces (id, name, color, description, page_document_id)
                VALUES (?,?,?,?,?)
                """, id, name, "#3730E0", "desc", pageId);
        jdbc.update("""
                INSERT INTO space_owners (space_id, user_id, is_responsible)
                VALUES (?,?,true)
                """, id, OWNER);
    }

    private static void insertFolder(UUID id, UUID spaceId, UUID parentId, String name, int position) {
        jdbc.update("""
                INSERT INTO folders (id, space_id, parent_folder_id, name, position, created_by, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?, 'active', now(), now())
                """, id, spaceId, parentId, name, position, OWNER);
    }

    private static void insertDoc(
            UUID id, UUID spaceId, UUID folderId, String title, String visibility, String status, BigDecimal score
    ) {
        jdbc.update("""
                INSERT INTO documents (
                  id, space_id, folder_id, title, body, status, doc_type, visibility, position,
                  reliability_score, updated_by, language
                ) VALUES (?, ?, ?, ?, '{"type":"doc"}'::jsonb, ?, 'page', ?, 0, ?, ?, 'fr')
                """, id, spaceId, folderId, title, status, visibility, score, OWNER);
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@x");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(3600))
                .build();
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
