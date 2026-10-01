// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.search;

import eu.socle.authz.AuthorizationService;
import eu.socle.search.SearchDtos.SearchHit;
import eu.socle.search.SearchDtos.SearchResponse;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests FTS + filtrage OpenFGA (listObjects batch). Le test de non-fuite est central.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC_SHARED = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_SECRET = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC_OTHER_SPACE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID DOC_OUTSIDE_GOV_SCOPE = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    static final UUID TAG_IAM = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;

    SearchService service;

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
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION trg_document_tags_search_vector()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF TG_OP = 'DELETE' THEN
                    PERFORM documents_rebuild_search_vector(OLD.document_id); RETURN OLD;
                  END IF;
                  PERFORM documents_rebuild_search_vector(NEW.document_id); RETURN NEW;
                END $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER document_tags_search_vector_aiud
                  AFTER INSERT OR UPDATE OR DELETE ON document_tags
                  FOR EACH ROW EXECUTE FUNCTION trg_document_tags_search_vector();
                """);
        jdbc.execute("CREATE INDEX idx_documents_search_vector ON documents USING GIN (search_vector)");

        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?), (?, ?)",
                SPACE_A, "Espace A", SPACE_B, "Espace B");
        jdbc.update("INSERT INTO tags (id, name) VALUES (?, ?)", TAG_IAM, "IAM");

        insertDoc(DOC_SHARED, SPACE_A, "Politique mot de passe",
                Map.of("type", "doc", "text", "Rotation trimestrielle des secrets"), "politique", null);
        insertDoc(DOC_SECRET, SPACE_A, "Plan incident confidentiel",
                Map.of("type", "doc", "text", "Playbook secret rotation urgente"), "politique", null);
        insertDoc(DOC_OTHER_SPACE, SPACE_B, "Procédure onboarding",
                Map.of("type", "doc", "text", "Rotation des comptes invités"), "procedure", null);
        // Hors scope gouvernance « tag IAM » mais lisible — doit apparaître en recherche
        insertDoc(DOC_OUTSIDE_GOV_SCOPE, SPACE_A, "Guide RH générique",
                Map.of("type", "doc", "text", "Rotation des congés annuels"), "guide", null);
        jdbc.update("INSERT INTO document_tags (document_id, tag_id) VALUES (?, ?)", DOC_SHARED, TAG_IAM);
    }

    @BeforeEach
    void setUp() {
        service = new SearchService(jdbc, userSyncService, authorizationService);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        // Check final : par défaut laisse passer les ids présélectionnés (tests de scope SQL).
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = inv.getArgument(1);
            return ids;
        });
        // État isolé entre tests (restore / tags ajoutés)
        jdbc.update("""
                UPDATE documents
                   SET body = '{"type":"doc","text":"Rotation trimestrielle des secrets"}'::jsonb
                 WHERE id = ?
                """, DOC_SHARED);
        jdbc.update("DELETE FROM document_tags WHERE document_id = ?", DOC_SECRET);
    }

    @Test
    void confidentialDocument_notInOpenFgaList_neverLeaksInResults() {
        // L'utilisateur ne peut lire que DOC_SHARED — DOC_SECRET matche FTS mais hors présélection
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED), List.of()));

        SearchResponse res = service.search(jwt(USER), "rotation", null, null, null, 50);

        assertThat(res.results()).extracting(SearchHit::id).containsExactly(DOC_SHARED);
        assertThat(res.results()).extracting(SearchHit::id).doesNotContain(DOC_SECRET);
        assertThat(res.results()).noneMatch(h ->
                h.title() != null && h.title().toLowerCase().contains("confidentiel"));
        assertThat(res.results()).noneMatch(h ->
                h.excerpt() != null && h.excerpt().toLowerCase().contains("playbook secret"));
        assertThat(res.totalIsEstimate()).isTrue();
        verify(authorizationService).readableScope(USER);
        verify(authorizationService).filterByDocumentViewer(eq(USER), any());
        verify(authorizationService, never()).hasRelation(any(), any(), any(), any());
    }

    @Test
    void governanceRoleScope_doesNotRestrictSearch_whenDocumentIsReadable() {
        // Reviewer « scopé tag IAM » : DOC_OUTSIDE_GOV_SCOPE n'a pas le tag, mais OpenFGA le rend lisible
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED, DOC_OUTSIDE_GOV_SCOPE), List.of()));

        SearchResponse res = service.search(jwt(USER), "rotation", null, null, null, 50);

        assertThat(res.results()).extracting(SearchHit::id)
                .contains(DOC_SHARED, DOC_OUTSIDE_GOV_SCOPE);
    }

    @Test
    void crossSpace_returnsHitsFromEachReadableSpace_pageByPage() {
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED, DOC_OTHER_SPACE), List.of()));

        SearchResponse res = service.search(jwt(USER), "rotation", null, null, null, 50);

        assertThat(res.results()).extracting(SearchHit::id)
                .containsExactlyInAnyOrder(DOC_SHARED, DOC_OTHER_SPACE);
        assertThat(res.results()).extracting(SearchHit::spaceId)
                .containsExactlyInAnyOrder(SPACE_A, SPACE_B);
    }

    @Test
    void noMatch_returnsEmptyList_notError() {
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED), List.of()));

        SearchResponse res = service.search(jwt(USER), "xyzzy-inexistant-token", null, null, null, 50);

        assertThat(res.results()).isEmpty();
        assertThat(res.total()).isZero();
        assertThat(res.query()).isEqualTo("xyzzy-inexistant-token");
    }

    @Test
    void emptyQuery_returnsEmptyWithoutCallingOpenFga() {
        SearchResponse res = service.search(jwt(USER), "   ", null, null, null, 50);
        assertThat(res.results()).isEmpty();
        verify(authorizationService, never()).readableScope(any());
    }

    @Test
    void filterByTagAndDocType() {
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED, DOC_OUTSIDE_GOV_SCOPE, DOC_OTHER_SPACE), List.of()));

        SearchResponse byTag = service.search(jwt(USER), "rotation", null, "IAM", null, 50);
        assertThat(byTag.results()).extracting(SearchHit::id).containsExactly(DOC_SHARED);

        SearchResponse byType = service.search(jwt(USER), "rotation", null, null, "procedure", 50);
        assertThat(byType.results()).extracting(SearchHit::id).containsExactly(DOC_OTHER_SPACE);

        SearchResponse bySpace = service.search(jwt(USER), "rotation", SPACE_B, null, null, 50);
        assertThat(bySpace.results()).extracting(SearchHit::id).containsExactly(DOC_OTHER_SPACE);
    }

    /**
     * Filtres spaceId+tag+docType en ET avec listObjectsDocumentIds — jamais un élargissement.
     * DOC_SECRET matche FTS + espace + type, mais hors OpenFGA → absent.
     */
    @Test
    void combinedFilters_intersectAuthorizedIds_doNotWidenPastOpenFga() {
        // Tag IAM sur DOC_SECRET aussi — le filtre tag matche le confidentiel
        jdbc.update("INSERT INTO document_tags (document_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                DOC_SECRET, TAG_IAM);

        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED), List.of()));

        SearchResponse res = service.search(
                jwt(USER), "rotation", SPACE_A, "IAM", "politique", 50);

        assertThat(res.results()).extracting(SearchHit::id).containsExactly(DOC_SHARED);
        assertThat(res.results()).extracting(SearchHit::id).doesNotContain(DOC_SECRET);
        // Même combo de filtres ne fait pas remonter un doc lisible hors combo (autre espace)
        assertThat(res.results()).extracting(SearchHit::id).doesNotContain(DOC_OTHER_SPACE);
    }

    /**
     * Restore = UPDATE de {@code body} → le trigger FTS doit réindexer (pas seulement create/update app).
     */
    @Test
    void bodyUpdate_likeRestore_refreshesSearchVector() {
        when(authorizationService.readableScope(USER)).thenReturn(new eu.socle.authz.AuthorizationService.ReadableScope(List.of(), List.of(), List.of(DOC_SHARED), List.of()));

        assertThat(service.search(jwt(USER), "trimestrielle", null, null, null, 10).results())
                .extracting(SearchHit::id).contains(DOC_SHARED);
        assertThat(service.search(jwt(USER), "hebdomadaire", null, null, null, 10).results())
                .isEmpty();

        // Mimique DocumentService.restore : UPDATE body (colonne couverte par le trigger)
        jdbc.update("""
                UPDATE documents
                   SET body = '{"type":"doc","text":"Rotation hebdomadaire post-restore"}'::jsonb,
                       updated_at = now()
                 WHERE id = ?
                """, DOC_SHARED);

        assertThat(service.search(jwt(USER), "hebdomadaire", null, null, null, 10).results())
                .extracting(SearchHit::id).containsExactly(DOC_SHARED);
        assertThat(service.search(jwt(USER), "trimestrielle", null, null, null, 10).results())
                .isEmpty();
    }

    /**
     * Au-delà du plafond ListObjects : pages {@code organisation} restent trouvables
     * via le filtre SQL même si listObjects renvoie une liste vide / tronquée.
     */
    @Test
    void organisationVisibility_beyondListObjectsCeiling_allFindable() {
        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(), List.of()));

        int n = 1200;
        String uniqueToken = "plafondxyzzyunique";
        for (int i = 0; i < n; i++) {
            UUID id = UUID.nameUUIDFromBytes(("org-pub-" + i).getBytes());
            jdbc.update("""
                    INSERT INTO documents (id, space_id, title, body, status, doc_type, visibility, updated_at)
                    VALUES (?, ?, ?, ?::jsonb, 'valide', 'guide', 'organisation', now())
                    ON CONFLICT (id) DO NOTHING
                    """,
                    id,
                    SPACE_A,
                    "Publique " + i,
                    "{\"type\":\"doc\",\"text\":\"" + uniqueToken + " document public\"}");
        }

        SearchResponse page = service.search(jwt(USER), uniqueToken, null, null, null, 100);
        assertThat(page.results()).hasSize(100);
        assertThat(page.results()).allMatch(h -> {
            String vis = jdbc.queryForObject(
                    "SELECT visibility FROM documents WHERE id = ?", String.class, h.id());
            return "organisation".equals(vis);
        });

        Integer orgCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM documents
                 WHERE deleted_at IS NULL AND visibility = 'organisation'
                   AND search_vector @@ plainto_tsquery('french', ?)
                """,
                Integer.class,
                uniqueToken);
        assertThat(orgCount).isGreaterThanOrEqualTo(n);

        // Ne pas polluer les autres tests FTS (« rotation »)
        jdbc.update("DELETE FROM documents WHERE visibility = 'organisation' AND title LIKE 'Publique %'");
    }

    /**
     * 1 500 documents {@code space} lisibles via S_view — tous trouvables (plus de plafond document ListObjects).
     */
    @Test
    void spaceVisibility_1500Docs_allFindableViaSpaceViewerScope() {
        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(SPACE_A), List.of(), List.of(), List.of()));

        int n = 1500;
        String uniqueToken = "spacebulkxyzzy";
        for (int i = 0; i < n; i++) {
            UUID id = UUID.nameUUIDFromBytes(("space-bulk-" + i).getBytes());
            jdbc.update("""
                    INSERT INTO documents (id, space_id, title, body, status, doc_type, visibility, updated_at)
                    VALUES (?, ?, ?, ?::jsonb, 'valide', 'guide', 'space', now())
                    ON CONFLICT (id) DO NOTHING
                    """,
                    id, SPACE_A, "SpaceBulk " + i,
                    "{\"type\":\"doc\",\"text\":\"" + uniqueToken + " page espace\"}");
        }

        SearchResponse page = service.search(jwt(USER), uniqueToken, null, null, null, 100);
        assertThat(page.results()).hasSize(100);

        Integer count = jdbc.query(
                """
                SELECT count(*) AS c FROM documents d
                 WHERE d.deleted_at IS NULL AND d.visibility = 'space'
                   AND d.space_id = ? AND d.title LIKE 'SpaceBulk %%'
                   AND d.search_vector @@ plainto_tsquery('french', ?)
                """,
                (rs, i) -> rs.getInt("c"),
                SPACE_A, uniqueToken).get(0);
        assertThat(count).isGreaterThanOrEqualTo(n);

        jdbc.update("DELETE FROM documents WHERE title LIKE 'SpaceBulk %'");
    }

    @Test
    void organisationColumnWithoutOpenFgaUserStar_excludedByFinalCheck_noExcerpt() {
        UUID docOrgDrift = UUID.fromString("a0a0a0a0-a0a0-a0a0-a0a0-a0a0a0a0a0a1");
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, doc_type, visibility, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'valide', 'guide', 'organisation', now())
                ON CONFLICT (id) DO NOTHING
                """,
                docOrgDrift, SPACE_A, "Org drift sans user star",
                "{\"type\":\"doc\",\"text\":\"orgdriftxyzzy sans wildcard\"}");

        // Présélection SQL inclut le doc (visibility=organisation), Check le refuse
        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(), List.of()));
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenReturn(List.of());

        SearchResponse res = service.search(jwt(USER), "orgdriftxyzzy", null, null, null, 50);

        assertThat(res.results()).isEmpty();
        assertThat(res.results()).noneMatch(h -> h.excerpt() != null && !h.excerpt().isBlank());
        jdbc.update("DELETE FROM documents WHERE id = ?", docOrgDrift);
    }

    @Test
    void orphanDirectAccessAlone_excludedFromSearchByFinalCheck() {
        // Présélection via D_direct (index) mais Check viewer refuse (plus de droit réel)
        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(DOC_SHARED), List.of()));
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenReturn(List.of());

        SearchResponse res = service.search(jwt(USER), "rotation", null, null, null, 50);

        assertThat(res.results()).isEmpty();
        assertThat(res.results()).noneMatch(h ->
                h.excerpt() != null && h.excerpt().toLowerCase().contains("secret"));
    }

    @Test
    void folderGrantAlone_documentFoundViaFViewPreselection() {
        UUID folderId = UUID.fromString("f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f0");
        UUID docInFolder = UUID.fromString("d0d0d0d0-d0d0-d0d0-d0d0-d0d0d0d0d0d0");
        jdbc.update("""
                INSERT INTO documents (id, space_id, folder_id, title, body, status, doc_type, visibility, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'valide', 'guide', 'restricted', now())
                ON CONFLICT (id) DO NOTHING
                """,
                docInFolder, SPACE_B, folderId, "Doc dossier seul",
                "{\"type\":\"doc\",\"text\":\"foldergrantxyzzy contenu\"}");

        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(), List.of(folderId)));

        SearchResponse res = service.search(jwt(USER), "foldergrantxyzzy", null, null, null, 50);

        assertThat(res.results()).extracting(SearchHit::id).containsExactly(docInFolder);
        jdbc.update("DELETE FROM documents WHERE id = ?", docInFolder);
    }

    @Test
    void authzRefill_completesPageWhenFinalCheckRejectsSomeCandidates() {
        // 4 docs FTS ; Check refuse les 2 premiers de chaque page de 2 → refill complète
        String token = "refillxyzzy";
        UUID d1 = UUID.nameUUIDFromBytes("refill-1".getBytes());
        UUID d2 = UUID.nameUUIDFromBytes("refill-2".getBytes());
        UUID d3 = UUID.nameUUIDFromBytes("refill-3".getBytes());
        UUID d4 = UUID.nameUUIDFromBytes("refill-4".getBytes());
        for (UUID id : List.of(d1, d2, d3, d4)) {
            jdbc.update("""
                    INSERT INTO documents (id, space_id, title, body, status, doc_type, visibility, updated_at)
                    VALUES (?, ?, ?, ?::jsonb, 'valide', 'guide', 'organisation', now())
                    ON CONFLICT (id) DO NOTHING
                    """,
                    id, SPACE_A, "Refill " + id,
                    "{\"type\":\"doc\",\"text\":\"" + token + " page\"}");
        }

        when(authorizationService.readableScope(USER)).thenReturn(
                new eu.socle.authz.AuthorizationService.ReadableScope(
                        List.of(), List.of(), List.of(), List.of()));
        // Refuse d1,d2 ; accepte d3,d4 — la 1ère page (limit=2) est rejetée, refill ramène d3,d4
        when(authorizationService.filterByDocumentViewer(eq(USER), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = inv.getArgument(1);
            return ids.stream().filter(id -> id.equals(d3) || id.equals(d4)).toList();
        });

        SearchResponse res = service.search(jwt(USER), token, null, null, null, 2);

        assertThat(res.results()).hasSize(2);
        assertThat(res.results()).extracting(SearchHit::id).containsExactlyInAnyOrder(d3, d4);
        assertThat(res.warning()).isNull();

        jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?, ?)", d1, d2, d3, d4);
    }

    private static void insertDoc(
            UUID id, UUID spaceId, String title, Map<String, Object> body, String docType, Instant updated
    ) {
        String bodyJson = "{\"type\":\"doc\",\"text\":\"" + body.get("text") + "\"}";
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, doc_type, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'valide', ?, coalesce(?, now()))
                """, id, spaceId, title, bodyJson, docType, updated == null ? null : java.sql.Timestamp.from(updated));
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
