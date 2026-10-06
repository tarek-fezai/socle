// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentService;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.search.SearchDtos.SearchHit;
import eu.socle.search.SearchService;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.GitStorageConsistencyValidator;
import eu.socle.storage.RelationalStorageConsistencyValidator;
import eu.socle.trash.TrashService;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit des jonctions Cross-domain (Must-have V1) — chaque test cible un point précis.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossDomainJunctionAuditTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_VISIBLE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_SECRET = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;

    @TempDir
    Path tempDir;

    SearchService searchService;
    Map<String, eu.socle.document.DocumentVersionEntity> versionRows;
    DocumentVersionRepository versionsForGit;

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
                  current_version_no INTEGER NOT NULL DEFAULT 1,
                  git_head_sha TEXT,
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
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?)", SPACE, "Espace restreint");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM documents");
        searchService = new SearchService(jdbc, userSyncService, authorizationService);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = inv.getArgument(1);
            return ids;
        });
        versionRows = new ConcurrentHashMap<>();
        versionsForGit = mockVersionRepo();
    }

    /**
     * Point 1 — Git × Search × Access : contenu Git projeté + filtre OpenFGA groupé.
     */
    @Test
    void point1_gitProjectedContent_filteredBylistObjectsDocumentIds() {
        GitDocumentStore git = new GitDocumentStore(versionsForGit, tempDir.resolve("p1"));
        Map<String, Object> body = tipTap("SecretJunctionKeyword");
        String head = git.createContent(DOC_VISIBLE, body, USER);
        git.createContent(DOC_SECRET, body, USER);

        // Projection Search (même pipeline DocumentService)
        insertProjected(DOC_VISIBLE, "Doc visible", "SecretJunctionKeyword", head);
        insertProjected(DOC_SECRET, "Doc secret", "SecretJunctionKeyword", "other");

        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(DOC_VISIBLE), List.of()));

        var res = searchService.search(jwt(USER), "SecretJunctionKeyword", null, null, null, 20);
        assertThat(res.results()).extracting(SearchHit::id).containsExactly(DOC_VISIBLE);
        assertThat(res.results()).extracting(SearchHit::id).doesNotContain(DOC_SECRET);
    }

    /**
     * Point 2 — Révocation × Search : filtre à la requête, pas à l'indexation.
     */
    @Test
    void point2_revokeAfterIndex_hidesFromSearchWithoutReindex() {
        insertProjected(DOC_VISIBLE, "Indexé", "RevokeSearchKeyword", null);

        AtomicReference<List<UUID>> readable = new AtomicReference<>(List.of(DOC_VISIBLE));
        when(authorizationService.readableScope(USER)).thenAnswer(inv ->
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), readable.get(), List.of()));

        assertThat(searchService.search(jwt(USER), "RevokeSearchKeyword", null, null, null, 10)
                .results()).extracting(SearchHit::id).containsExactly(DOC_VISIBLE);

        // Révocation OpenFGA simulée — D_direct vide ; FTS inchangé
        readable.set(List.of());

        assertThat(searchService.search(jwt(USER), "RevokeSearchKeyword", null, null, null, 10)
                .results()).isEmpty();
        // Le document reste indexé en base
        Integer stillIndexed = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ? AND search_vector @@ plainto_tsquery('french', 'RevokeSearchKeyword')",
                Integer.class, DOC_VISIBLE);
        assertThat(stillIndexed).isEqualTo(1);
    }

    /**
     * Point 3 — Restore exige editor OpenFGA (même garde que update).
     */
    @Test
    void point3_restore_requiresEditorRelation_sameAsUpdate() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC_VISIBLE);
        entity.setSpaceId(SPACE);
        entity.setTitle("T");
        entity.setBody(tipTap("current"));
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(2);
        when(documentRepository.findActiveById(DOC_VISIBLE)).thenReturn(Optional.of(entity));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé"))
                .when(authorizationService).requireDocumentRelation(USER, DOC_VISIBLE, "editor");

        DocumentService service = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, reliabilityScoreService, mock(TrashService.class));

        assertThatThrownBy(() -> service.restore(jwt(USER), DOC_VISIBLE, 1, 2))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService).requireDocumentRelation(USER, DOC_VISIBLE, "editor");
    }

    /**
     * Point 4 — Soumission sans version dupliquée : pas d'écriture DocumentStore.
     */
    @Test
    void point4_recordSubmission_doesNotTouchDocumentStore() {
        DocumentStore store = mock(DocumentStore.class);

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        Map<String, Object> docRow = new java.util.HashMap<>();
        docRow.put("current_version_no", 3);
        docRow.put("git_head_sha", "oldsha");
        when(mockJdbc.queryForList(org.mockito.ArgumentMatchers.contains("current_version_no"), eq(DOC_VISIBLE)))
                .thenReturn(List.of(docRow));
        when(mockJdbc.update(any(String.class), any(Object.class))).thenReturn(1);
        when(mockJdbc.update(any(String.class), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class)))
                .thenReturn(1);

        var activities = new eu.socle.document.ApprovalActivitiesImpl(
                mockJdbc, auditService, reliabilityScoreService, store,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(eu.socle.document.ApprovalRoleResolver.class));

        activities.recordSubmission(
                DOC_VISIBLE, USER, UUID.randomUUID(), UUID.randomUUID(), "wf", 1, 24);

        verify(store, never()).archiveVersion(any(), any(Integer.class), any(), any(), any(), any());
        verify(store, never()).writeCurrentContent(any(), any(), any(), any(), any(), any());
    }

    /**
     * Point 7b — Bascule git→relational : docs avec git_head_sha mais body vide → échec.
     */
    @Test
    void point7b_relationalProvider_withGitShaButEmptyBody_failsStartup() {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, git_head_sha, updated_at)
                VALUES (?, ?, 'Orphelin inverse', '{}'::jsonb, 'brouillon', 'deadbeef', now())
                """, DOC_SECRET, SPACE);
        RelationalStorageConsistencyValidator validator =
                new RelationalStorageConsistencyValidator(jdbc);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git→relational")
                .hasMessageContaining("body JSONB");
    }

    /**
     * Point 7b (positif) — après Git normal, body TipTap reste peuplé → relational OK.
     */
    @Test
    void point7b_relationalProvider_withGitShaAndValidBody_starts() {
        insertProjected(DOC_SECRET, "Sync OK", "ContenuPresent", "abc123");
        RelationalStorageConsistencyValidator validator =
                new RelationalStorageConsistencyValidator(jdbc);
        validator.run(new DefaultApplicationArguments()); // ne lance pas
    }

    private void insertProjected(UUID id, String title, String text, String gitHead) {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, git_head_sha, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'brouillon', ?, now())
                """, id, SPACE, title,
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                        + text + "\"}]}]}",
                gitHead);
    }

    private DocumentVersionRepository mockVersionRepo() {
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.save(any())).thenAnswer(inv -> {
            var e = inv.getArgument(0, eu.socle.document.DocumentVersionEntity.class);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            if (e.getCreatedAt() == null) {
                e.setCreatedAt(Instant.now());
            }
            versionRows.put(e.getDocumentId() + ":" + e.getVersionNo(), e);
            return e;
        });
        when(versions.findByDocumentIdAndVersionNo(any(), anyInt())).thenAnswer(inv ->
                Optional.ofNullable(versionRows.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(versions.findByDocumentIdOrderByVersionNoDesc(any(), any(Pageable.class))).thenAnswer(inv -> {
            UUID doc = inv.getArgument(0);
            Pageable p = inv.getArgument(1);
            List<eu.socle.document.DocumentVersionEntity> all = versionRows.values().stream()
                    .filter(v -> doc.equals(v.getDocumentId()))
                    .sorted(Comparator.comparingInt(
                            eu.socle.document.DocumentVersionEntity::getVersionNo).reversed())
                    .toList();
            return new PageImpl<>(new ArrayList<>(all), p, all.size());
        });
        return versions;
    }

    private static Map<String, Object> tipTap(String text) {
        Map<String, Object> textNode = new HashMap<>();
        textNode.put("type", "text");
        textNode.put("text", text);
        Map<String, Object> para = new HashMap<>();
        para.put("type", "paragraph");
        para.put("content", List.of(textNode));
        return Map.of("type", "doc", "content", List.of(para));
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
