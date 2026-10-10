// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.CreateStoreResponse;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import eu.socle.authz.AuthorizationService;
import eu.socle.config.SocleProperties;
import eu.socle.favorites.FavoriteDtos.FavoriteItem;
import eu.socle.favorites.FavoriteService;
import eu.socle.identity.IdentityFacade;
import eu.socle.notification.NotificationService;
import eu.socle.search.SearchDtos.SearchHit;
import eu.socle.search.SearchService;
import eu.socle.space.SpaceDtos.SpaceView;
import eu.socle.space.SpaceService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sécurité catalogue (espaces / favoris / recherche / notifications) contre Postgres + OpenFGA réels.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogMetadataSecurityIT {

    private static final Logger log = LoggerFactory.getLogger(CatalogMetadataSecurityIT.class);
    static final String OPENFGA_IMAGE = "openfga/openfga:v1.8.16";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID VIEWER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID OUTSIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_ORG = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    static final UUID DOC_SPACE = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final UUID DOC_SECRET = UUID.fromString("b0000000-0000-4000-8000-000000000003");
    static final UUID TAG_IAM = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

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

    static JdbcTemplate jdbc;
    static OpenFgaClient fgaClient;
    static AuthorizationService authz;

    @Mock UserSyncService userSyncService;
    @Mock IdentityFacade identityFacade;

    SpaceService spaceService;
    FavoriteService favoriteService;
    SearchService searchService;
    NotificationService notificationService;

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
                  deleted_at TIMESTAMPTZ
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
                  id UUID PRIMARY KEY, space_id UUID NOT NULL, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL REFERENCES spaces(id),
                  folder_id UUID,
                  title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}'::jsonb,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  doc_type TEXT,
                  visibility TEXT NOT NULL DEFAULT 'space',
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ,
                  search_vector tsvector
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (
                  id UUID PRIMARY KEY, name TEXT NOT NULL UNIQUE, color TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                  PRIMARY KEY (document_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE favorites (
                  user_id UUID NOT NULL,
                  target_type TEXT NOT NULL,
                  target_id UUID NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  PRIMARY KEY (user_id, target_type, target_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE notifications (
                  id UUID PRIMARY KEY,
                  user_id UUID NOT NULL,
                  type TEXT NOT NULL,
                  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                  read_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION documents_rebuild_search_vector(p_document_id UUID)
                RETURNS void LANGUAGE plpgsql AS $$
                DECLARE tag_text TEXT;
                BEGIN
                  SELECT coalesce(string_agg(t.name, ' '), '') INTO tag_text
                    FROM document_tags dt JOIN tags t ON t.id = dt.tag_id
                   WHERE dt.document_id = p_document_id;
                  UPDATE documents d SET search_vector =
                      setweight(to_tsvector('french', coalesce(d.title, '')), 'A')
                   || setweight(to_tsvector('french', coalesce(d.body::text, '')), 'B')
                   || setweight(to_tsvector('french', coalesce(tag_text, '')), 'C')
                   WHERE d.id = p_document_id;
                END $$;
                """);

        String apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        fgaClient = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));
        CreateStoreResponse store = fgaClient.createStore(new CreateStoreRequest().name("catalog-it")).get();
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

        jdbc.update("INSERT INTO users (id, email, display_name, avatar_initials, status) VALUES (?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
                OWNER, "owner@x", "Owner One", "OO", "active",
                VIEWER, "viewer@x", "Viewer Two", "VT", "active",
                OUTSIDER, "out@x", "Out Sider", "OS", "active");
        jdbc.update("INSERT INTO spaces (id, name, color, description) VALUES (?,?,?,?)",
                SPACE, "Identité", "#3730E0", "IAM et gouvernance");
        jdbc.update("INSERT INTO space_owners (space_id, user_id, is_responsible) VALUES (?,?,true)", SPACE, OWNER);
        insertDoc(DOC_ORG, "Page organisation provisioning", "organisation", "valide");
        insertDoc(DOC_SPACE, "Procédure espace", "space", "valide");
        insertDoc(DOC_SECRET, "Secret provisioning", "restricted", "brouillon");
        jdbc.update("INSERT INTO tags (id, name, color) VALUES (?,?,?)", TAG_IAM, "IAM", "#3730E0");
        jdbc.update("INSERT INTO document_tags (document_id, tag_id) VALUES (?,?)", DOC_ORG, TAG_IAM);
        jdbc.queryForObject("SELECT documents_rebuild_search_vector(?)", Object.class, DOC_ORG);
        jdbc.queryForObject("SELECT documents_rebuild_search_vector(?)", Object.class, DOC_SPACE);
        jdbc.queryForObject("SELECT documents_rebuild_search_vector(?)", Object.class, DOC_SECRET);

        authz.grantPermission("space", SPACE, "owner", "user", OWNER);
        authz.grantPermission("space", SPACE, "viewer", "user", VIEWER);
        authz.provisionDocumentAccess(DOC_ORG, SPACE, OWNER, "organisation");
        authz.provisionDocumentAccess(DOC_SPACE, SPACE, OWNER, "space");
        authz.provisionDocumentAccess(DOC_SECRET, SPACE, OWNER, "restricted");
    }

    @BeforeEach
    void setUp() {
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        var audit = mock(eu.socle.audit.AuditService.class);
        spaceService = new SpaceService(jdbc, userSyncService, authz, audit, identityFacade);
        favoriteService = new FavoriteService(
                jdbc, userSyncService, authz, Clock.fixed(NOW, ZoneOffset.UTC));
        searchService = new SearchService(jdbc, userSyncService, authz);
        notificationService = new NotificationService(jdbc, userSyncService, MAPPER);
        jdbc.update("DELETE FROM favorites");
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void documentCount_excludesDocumentsCallerCannotView() {
        SpaceView ownerView = spaceService.list(jwt(OWNER)).stream()
                .filter(s -> SPACE.equals(s.id())).findFirst().orElseThrow();
        SpaceView viewerView = spaceService.list(jwt(VIEWER)).stream()
                .filter(s -> SPACE.equals(s.id())).findFirst().orElseThrow();
        SpaceView outsiderView = spaceService.list(jwt(OUTSIDER)).stream()
                .filter(s -> SPACE.equals(s.id())).findFirst().orElseThrow();

        assertThat(ownerView.documentCount()).isEqualTo(3);
        assertThat(viewerView.documentCount()).isEqualTo(2);
        assertThat(outsiderView.documentCount()).isEqualTo(1);
        assertThat(outsiderView.membership()).isEqualTo("public-only");
        assertThat(ownerView.memberPreview()).isNotEmpty();
        assertThat(ownerView.description()).isEqualTo("IAM et gouvernance");
    }

    @Test
    void favorite_inaccessible_hasNoMetadata() {
        jdbc.update("""
                INSERT INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (?, 'document', ?, now()), (?, 'document', ?, now())
                """, OUTSIDER, DOC_SECRET, OUTSIDER, DOC_ORG);

        List<FavoriteItem> list = favoriteService.list(jwt(OUTSIDER));
        FavoriteItem secret = list.stream().filter(i -> DOC_SECRET.equals(i.targetId())).findFirst().orElseThrow();
        FavoriteItem org = list.stream().filter(i -> DOC_ORG.equals(i.targetId())).findFirst().orElseThrow();

        assertThat(secret.title()).isNull();
        assertThat(secret.status()).isNull();
        assertThat(secret.spaceId()).isNull();
        assertThat(secret.spaceName()).isNull();
        assertThat(org.title()).isEqualTo("Page organisation provisioning");
        assertThat(org.spaceName()).isEqualTo("Identité");
    }

    @Test
    void search_omitsInaccessibleHit_andExposesVisibleTagsAndFavorite() {
        jdbc.update("""
                INSERT INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (?, 'document', ?, now())
                """, OUTSIDER, DOC_ORG);

        var res = searchService.search(jwt(OUTSIDER), "provisioning", null, null, null, 20);
        assertThat(res.results()).extracting(SearchHit::id).contains(DOC_ORG).doesNotContain(DOC_SECRET, DOC_SPACE);
        SearchHit hit = res.results().stream().filter(h -> DOC_ORG.equals(h.id())).findFirst().orElseThrow();
        assertThat(hit.tags()).extracting(t -> t.name()).contains("IAM");
        assertThat(hit.isFavorite()).isTrue();
    }

    @Test
    void readAll_onlyAffectsCaller() {
        UUID nA = UUID.randomUUID();
        UUID nB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, payload, created_at)
                VALUES (?, ?, 'comment_mention', '{}'::jsonb, now()),
                       (?, ?, 'comment_mention', '{}'::jsonb, now())
                """, nA, OWNER, nB, VIEWER);

        var result = notificationService.markAllRead(jwt(OWNER));
        assertThat(result.updated()).isEqualTo(1);
        Integer ownerUnread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Integer.class, OWNER);
        Integer viewerUnread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Integer.class, VIEWER);
        assertThat(ownerUnread).isZero();
        assertThat(viewerUnread).isEqualTo(1);
    }

    @Test
    void listSpacesAndFavorites_50items_noNPlusOneBudget() {
        List<UUID> extraSpaces = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            UUID sid = UUID.randomUUID();
            extraSpaces.add(sid);
            jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?)", sid, "Espace " + i);
            jdbc.update("INSERT INTO space_owners (space_id, user_id, is_responsible) VALUES (?,?,true)",
                    sid, OWNER);
            authz.grantPermission("space", sid, "owner", "user", OWNER);
            UUID doc = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO documents (id, space_id, title, visibility, status)
                    VALUES (?, ?, ?, 'space', 'valide')
                    """, doc, sid, "Doc " + i);
            authz.provisionDocumentAccess(doc, sid, OWNER, "space");
            jdbc.update("""
                    INSERT INTO favorites (user_id, target_type, target_id, created_at)
                    VALUES (?, 'document', ?, now())
                    """, OWNER, doc);
        }
        jdbc.update("""
                INSERT INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (?, 'document', ?, now())
                """, OWNER, DOC_ORG);

        long t0 = System.nanoTime();
        List<SpaceView> spaces = spaceService.list(jwt(OWNER));
        long spacesMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        List<FavoriteItem> favs = favoriteService.list(jwt(OWNER));
        long favsMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("catalog perf: spaces={} in {} ms, favorites={} in {} ms",
                spaces.size(), spacesMs, favs.size(), favsMs);

        assertThat(spaces.size()).isGreaterThanOrEqualTo(50);
        assertThat(favs.size()).isGreaterThanOrEqualTo(50);
        assertThat(spacesMs).isLessThan(15_000);
        assertThat(favsMs).isLessThan(15_000);

        jdbc.update("DELETE FROM favorites WHERE user_id = ?", OWNER);
        for (UUID sid : extraSpaces) {
            jdbc.update("DELETE FROM documents WHERE space_id = ?", sid);
            jdbc.update("DELETE FROM space_owners WHERE space_id = ?", sid);
            jdbc.update("DELETE FROM spaces WHERE id = ?", sid);
        }
    }

    private static void insertDoc(UUID id, String title, String visibility, String status) {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, visibility, status)
                VALUES (?, ?, ?, '{"type":"doc"}'::jsonb, ?, ?)
                """, id, SPACE, title, visibility, status);
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
