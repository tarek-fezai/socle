package eu.socle.storage;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.search.SearchDtos.SearchHit;
import eu.socle.search.SearchService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mode Git : après create/update, la projection Postgres (documents.body) reste
 * indexable — Search trouve immédiatement le contenu.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitSearchProjectionTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    @TempDir
    Path tempDir;

    SearchService searchService;
    GitDocumentStore gitStore;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID NOT NULL REFERENCES spaces(id),
                  folder_id UUID,
                  title TEXT NOT NULL,
                  body JSONB NOT NULL,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  doc_type TEXT,
                  visibility TEXT NOT NULL DEFAULT 'space',
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ,
                  search_vector tsvector
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (id UUID PRIMARY KEY, name TEXT NOT NULL UNIQUE, color TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                  PRIMARY KEY (document_id, tag_id)
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
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION trg_documents_search_vector()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM documents_rebuild_search_vector(NEW.id); RETURN NEW; END $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER documents_search_vector_aiud
                  AFTER INSERT OR UPDATE OF title, body ON documents
                  FOR EACH ROW EXECUTE FUNCTION trg_documents_search_vector();
                """);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?)", SPACE, "Espace Git");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM documents");
        DocumentVersionRepository versions = mockVersionRepo();
        gitStore = new GitDocumentStore(versions, tempDir.resolve("git-repo"));
        searchService = new SearchService(jdbc, userSyncService, authorizationService);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(DOC), List.of()));
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.List<UUID> ids = inv.getArgument(1);
            return ids;
        });
    }

    @Test
    void gitCreateThenProjectBody_isSearchableImmediately() {
        Map<String, Object> body = DocumentStoreContractTest.tipTap("MotUniqueGitSearchable");
        gitStore.createContent(DOC, body, USER);

        // Projection Search = même contrat que DocumentService (colonne body + trigger FTS)
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'brouillon', now())
                """, DOC, SPACE, "Doc Git",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"MotUniqueGitSearchable\"}]}]}");

        var res = searchService.search(jwt(USER), "MotUniqueGitSearchable", null, null, null, 10);
        assertThat(res.results()).extracting(SearchHit::id).containsExactly(DOC);
    }

    @Test
    void gitUpdate_refreshesProjection_searchSeesNewTerm() {
        Map<String, Object> v1 = DocumentStoreContractTest.tipTap("AncienTermeGit");
        String head = gitStore.createContent(DOC, v1, USER);
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'brouillon', now())
                """, DOC, SPACE, "Doc Git",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"AncienTermeGit\"}]}]}");

        Map<String, Object> v2 = DocumentStoreContractTest.tipTap("NouveauTermeGit");
        gitStore.archiveVersion(DOC, 1, v1, USER, "edit");
        gitStore.writeCurrentContent(DOC, v2, USER, "edit", head);
        jdbc.update("""
                UPDATE documents SET body = ?::jsonb, updated_at = now() WHERE id = ?
                """,
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"NouveauTermeGit\"}]}]}",
                DOC);

        assertThat(searchService.search(jwt(USER), "NouveauTermeGit", null, null, null, 10)
                .results()).extracting(SearchHit::id).containsExactly(DOC);
        assertThat(searchService.search(jwt(USER), "AncienTermeGit", null, null, null, 10)
                .results()).isEmpty();
    }

    private static DocumentVersionRepository mockVersionRepo() {
        Map<String, eu.socle.document.DocumentVersionEntity> rows = new ConcurrentHashMap<>();
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.save(any())).thenAnswer(inv -> {
            var e = inv.getArgument(0, eu.socle.document.DocumentVersionEntity.class);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            if (e.getCreatedAt() == null) {
                e.setCreatedAt(Instant.now());
            }
            rows.put(e.getDocumentId() + ":" + e.getVersionNo(), e);
            return e;
        });
        when(versions.findByDocumentIdAndVersionNo(any(), anyInt())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(versions.findByDocumentIdOrderByVersionNoDesc(eq(DOC), any(Pageable.class))).thenAnswer(inv -> {
            Pageable p = inv.getArgument(1);
            List<eu.socle.document.DocumentVersionEntity> all = rows.values().stream()
                    .filter(v -> DOC.equals(v.getDocumentId()))
                    .sorted(Comparator.comparingInt(eu.socle.document.DocumentVersionEntity::getVersionNo).reversed())
                    .toList();
            return new PageImpl<>(new ArrayList<>(all), p, all.size());
        });
        return versions;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
