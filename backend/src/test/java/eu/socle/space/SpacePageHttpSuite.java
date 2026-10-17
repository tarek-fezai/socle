// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import dev.openfga.sdk.api.client.model.ClientListUsersResponse;
import dev.openfga.sdk.api.client.model.ClientWriteResponse;
import eu.socle.SocleBackendApplication;
import eu.socle.audit.AuditActions;
import eu.socle.retention.RetentionPurgeService;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.StorageProperties;
import eu.socle.web.ApiErrors;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page d'espace, exécutée deux fois (relational et git) par les sous-classes.
 */
abstract class SpacePageHttpSuite {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final UUID CONTRIBUTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID READER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String PAGE_TEXT = "Bonjour espace";
    static final String SPACE_NAME = "Identité & accès";

    static RSAKey rsaKey;

    static {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("space-page-test").generate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TestConfiguration
    static class JwtConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentStore documentStore;
    @Autowired StorageProperties storageProperties;
    @Autowired RetentionPurgeService retentionPurge;

    @MockBean WorkflowClient workflowClient;
    @MockBean OpenFgaClient openFgaClient;

    UUID spaceId;
    UUID folderId;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        stubFga();
        jdbc.execute("SET session_replication_role = replica");
        try {
            jdbc.update("DELETE FROM audit_log_events");
            jdbc.update("DELETE FROM trash_items");
            jdbc.update("DELETE FROM favorites");
            jdbc.update("DELETE FROM document_versions");
            jdbc.update("DELETE FROM git_purge_queue");
            jdbc.update("DELETE FROM documents");
            jdbc.update("DELETE FROM folders");
            jdbc.update("DELETE FROM group_members");
            jdbc.update("DELETE FROM groups");
            jdbc.update("DELETE FROM spaces");
            jdbc.update("DELETE FROM user_platform_roles");
            jdbc.update("DELETE FROM user_identities");
            jdbc.update("DELETE FROM users");
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
        seed(CONTRIBUTOR, "contributeur@example.com");
        seed(READER, "lecteur@example.com");
        seed(STRANGER, "externe@example.com");

        spaceId = UUID.randomUUID();
        folderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spaces (id, name, default_visibility)
                VALUES (?, ?, 'organisation')
                """, spaceId, SPACE_NAME);
        jdbc.update("""
                INSERT INTO folders (id, space_id, name, created_by, status)
                VALUES (?, ?, 'Archives', ?, 'active')
                """, folderId, spaceId, CONTRIBUTOR);
    }

    @Test
    void get_beforeWrite_is204_andNoDocument() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/page").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isNoContent());
        assertThat(pageCount()).isZero();
    }

    @Test
    void concurrentPut_createsASinglePage_thenVersions() throws Exception {
        String body = pageBody(PAGE_TEXT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<MvcResult> first = new AtomicReference<>();
        AtomicReference<MvcResult> second = new AtomicReference<>();
        Future<?> a = pool.submit(() -> putTogether(start, body, first));
        Future<?> b = pool.submit(() -> putTogether(start, body, second));
        start.countDown();
        a.get();
        b.get();
        pool.shutdown();

        assertThat(first.get().getResponse().getStatus()).isEqualTo(200);
        assertThat(second.get().getResponse().getStatus()).isEqualTo(200);
        String id1 = MAPPER.readTree(first.get().getResponse().getContentAsString()).path("id").asText();
        String id2 = MAPPER.readTree(second.get().getResponse().getContentAsString()).path("id").asText();
        assertThat(id1).isEqualTo(id2);
        assertThat(pageCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT page_document_id::text FROM spaces WHERE id = ?", String.class, spaceId))
                .isEqualTo(id1);
        assertThat(jdbc.queryForObject("SELECT folder_id FROM documents WHERE id = ?::uuid", UUID.class, id1))
                .isNull();
        int v1 = MAPPER.readTree(first.get().getResponse().getContentAsString()).path("currentVersionNo").asInt();
        int v2 = MAPPER.readTree(second.get().getResponse().getContentAsString()).path("currentVersionNo").asInt();
        assertThat(List.of(v1, v2)).containsExactlyInAnyOrder(1, 2);

        JsonNode created = awaitAudit(AuditActions.DOCUMENT_CREATED);
        assertThat(created.path("spacePage").asBoolean()).isTrue();
        assertThat(created.path("spaceId").asText()).isEqualTo(spaceId.toString());
        JsonNode updated = awaitAudit(AuditActions.DOCUMENT_UPDATED);
        assertThat(updated.path("spacePage").asBoolean()).isTrue();
        assertThat(updated.path("spaceId").asText()).isEqualTo(spaceId.toString());
        assertContentStored(UUID.fromString(id1), PAGE_TEXT);
    }

    @Test
    void listsExcludeThePage_searchLabelsIt() throws Exception {
        String pageId = createPage();
        MvcResult sibling = mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Consigne","body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Consigne frère"}]}]},"spaceId":"%s"}
                                """.formatted(spaceId)))
                .andExpect(status().isCreated())
                .andReturn();
        String siblingId = MAPPER.readTree(sibling.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/tree").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[?(@.id == '" + pageId + "')]").isEmpty())
                .andExpect(jsonPath("$.documents[?(@.id == '" + siblingId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/spaces/" + spaceId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.pageDocumentId").value(pageId));

        mockMvc.perform(get("/api/v1/documents").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[?(@.id == '" + pageId + "')]").isEmpty())
                .andExpect(jsonPath("$.results[?(@.id == '" + siblingId + "')]").isNotEmpty());

        mockMvc.perform(put("/api/v1/favorites/document/" + pageId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/favorites").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.targetId == '" + pageId + "')]").isEmpty());

        mockMvc.perform(get("/api/v1/home").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..documentId").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(pageId))));

        mockMvc.perform(get("/api/v1/search").param("q", "Bonjour").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[?(@.id == '" + pageId + "')].title")
                        .value(org.hamcrest.Matchers.contains("Espace — " + SPACE_NAME)))
                .andExpect(jsonPath("$.results[?(@.id == '" + pageId + "')].spaceId")
                        .value(org.hamcrest.Matchers.contains(spaceId.toString())));
    }

    @Test
    void pageAlone_isLocked_spaceCarriesIt() throws Exception {
        String pageId = createPage();

        mockMvc.perform(post("/api/v1/documents/" + pageId + "/move")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + folderId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.SPACE_PAGE_LOCKED));
        mockMvc.perform(delete("/api/v1/trash/documents/" + pageId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.SPACE_PAGE_LOCKED));

        mockMvc.perform(delete("/api/v1/trash/spaces/" + spaceId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();
        UUID docTrash = jdbc.queryForObject(
                "SELECT id FROM trash_items WHERE resource_type = 'document' AND resource_id = ?::uuid",
                UUID.class, pageId);
        mockMvc.perform(post("/api/v1/trash/" + docTrash + "/restore").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.SPACE_PAGE_LOCKED));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();

        UUID spaceTrash = jdbc.queryForObject(
                "SELECT id FROM trash_items WHERE resource_type = 'space' AND resource_id = ?",
                UUID.class, spaceId);
        mockMvc.perform(post("/api/v1/trash/" + spaceTrash + "/restore").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT folder_id FROM documents WHERE id = ?::uuid", UUID.class, pageId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT space_id FROM documents WHERE id = ?::uuid", UUID.class, pageId))
                .isEqualTo(spaceId);
    }

    @Test
    void rename_syncsPageTitle() throws Exception {
        String pageId = createPage();
        mockMvc.perform(put("/api/v1/spaces/" + spaceId)
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Modes opératoires\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT title FROM documents WHERE id = ?::uuid", String.class, pageId))
                .isEqualTo("Modes opératoires");
    }

    @Test
    void rights_readerContributorStrangerAndReadOnlyPat() throws Exception {
        String pageId = createPage();

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/page").header("Authorization", jwt(READER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pageId));
        mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                        .header("Authorization", jwt(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("interdit")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/page").header("Authorization", jwt(STRANGER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                        .header("Authorization", jwt(STRANGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("interdit")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("par le contributeur")))
                .andExpect(status().isOk());

        String pat = createReadPat();
        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/page").header("Authorization", "Bearer " + pat))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                        .header("Authorization", "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("pat")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_SCOPE_INSUFFICIENT));
    }

    @Test
    void zipExport_includesSpacePageAtRoot() throws Exception {
        createPage();
        MvcResult zip = mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/export")
                        .param("format", "zip")
                        .header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andReturn();
        String markdown = readZipEntry(zip.getResponse().getContentAsByteArray(), "_espace.md");
        assertThat(markdown).contains(PAGE_TEXT);
        assertThat(storageProperties.getProvider()).isIn("relational", "git");
    }

    @Test
    void documentPut_sameTitleAuditsSpacePage_otherTitleIs409() throws Exception {
        String pageId = createPage();
        String other = """
                {"title":"Autre titre","body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"autre"}]}]}}
                """;
        mockMvc.perform(put("/api/v1/documents/" + pageId)
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(other))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.SPACE_PAGE_TITLE_LOCKED));
        assertThat(jdbc.queryForObject("SELECT title FROM documents WHERE id = ?::uuid", String.class, pageId))
                .isEqualTo(SPACE_NAME);

        String same = """
                {"title":"%s","body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"via document"}]}]}}
                """.formatted(SPACE_NAME);
        mockMvc.perform(put("/api/v1/documents/" + pageId)
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(same))
                .andExpect(status().isOk());
        JsonNode updated = awaitAudit(AuditActions.DOCUMENT_UPDATED);
        assertThat(updated.path("spacePage").asBoolean()).isTrue();
        assertThat(updated.path("spaceId").asText()).isEqualTo(spaceId.toString());
    }

    @Test
    void retention_archivedSpaceWithPage_purgesTogether() throws Exception {
        String pageId = createPage();
        String siblingId = createSibling();
        archiveOld(pageId, siblingId);

        var result = retentionPurge.run();

        assertThat(result.failures()).isZero();
        assertThat(result.archivedSpacesPurged()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM documents WHERE id = ?::uuid", pageId)).isZero();
        assertThat(countRows("SELECT count(*) FROM documents WHERE id = ?::uuid", siblingId)).isZero();
        assertThat(countRows("SELECT count(*) FROM spaces WHERE id = ?", spaceId)).isZero();
        awaitAudit(AuditActions.SPACE_PURGED);
        assertPurgeQueueClean();
    }

    @Test
    void retention_archivedPage_stays_isolatedSiblingIsPurged() throws Exception {
        String pageId = createPage();
        String siblingId = createSibling();
        String liveId = createSibling();
        archiveOld(pageId, siblingId);

        var result = retentionPurge.run();

        assertThat(result.failures()).isZero();
        assertThat(result.archivedSpacesPurged()).isZero();
        assertThat(result.archivedDocumentsPurged()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM documents WHERE id = ?::uuid", pageId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT page_document_id::text FROM spaces WHERE id = ?", String.class, spaceId))
                .isEqualTo(pageId);
        assertThat(countRows("SELECT count(*) FROM documents WHERE id = ?::uuid", siblingId)).isZero();
        assertThat(countRows("SELECT count(*) FROM documents WHERE id = ?::uuid", liveId)).isEqualTo(1);
        assertPurgeQueueClean();
    }

    /**
     * Jeu de la maquette « Espace — accueil » : Identité & accès, 7 documents,
     * 2 dossiers, Procédures 3 dont 1 en revue, Référence 2, 2 à la racine dont 1 brouillon,
     * fiabilité moyenne 91 %.
     */
    @Test
    void get_matchesSpaceHomeMockup() throws Exception {
        UUID claire = UUID.fromString("c2222222-2222-4222-8222-222222222222");
        UUID yanis = UUID.fromString("c3333333-3333-4333-8333-333333333333");
        seed(claire, "claire.dubois@example.com");
        seed(yanis, "yanis.m@example.com");
        name(CONTRIBUTOR, "Tarek Fezai", "TF");

        String auth = jwt(CONTRIBUTOR, "Tarek Fezai");
        MvcResult space = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Identité & accès\",\"color\":\"#3730E0\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String homeId = MAPPER.readTree(space.getResponse().getContentAsString()).path("id").asText();

        String procedures = createFolder(auth, homeId, "Procédures");
        String reference = createFolder(auth, homeId, "Référence");
        seedDoc(auth, homeId, procedures, "Provisioning des comptes", "procedure", "valide", 88, null);
        seedDoc(auth, homeId, procedures, "Dé-provisioning des comptes", "procedure", "valide", 88, null);
        seedDoc(auth, homeId, procedures, "Revue périodique des droits", "procedure", "en_revue", 88,
                Instant.parse("2026-09-20T08:00:00Z"));
        seedDoc(auth, homeId, reference, "Rôles & habilitations", "Référence", "valide", 95, null);
        seedDoc(auth, homeId, reference, "Glossaire des permissions", "Référence", "valide", 95, null);
        seedDoc(auth, homeId, null, "Politique de gestion des accès", "politique", "valide", 92, null);
        seedDoc(auth, homeId, null, "Registre des accès à privilèges", "politique", "brouillon", 91, null);

        mockMvc.perform(put("/api/v1/spaces/" + homeId + "/page")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("Accueil de l'espace")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Équipe Identité\"}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/spaces/" + homeId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(SPACE_NAME))
                .andExpect(jsonPath("$.documentCount").value(7))
                .andExpect(jsonPath("$.pageDocumentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("actif"))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.owner.name").value("Équipe Identité"))
                .andExpect(jsonPath("$.owner.owner.displayName").value("Tarek Fezai"))
                .andExpect(jsonPath("$.owner.owner.initials").value("TF"))
                .andExpect(jsonPath("$.teamCount").value(1))
                .andExpect(jsonPath("$.statusCounts.validated").value(5))
                .andExpect(jsonPath("$.statusCounts.inReview").value(1))
                .andExpect(jsonPath("$.statusCounts.draft").value(1))
                .andExpect(jsonPath("$.folders[?(@.name == 'Procédures')].documentCount")
                        .value(org.hamcrest.Matchers.contains(3)))
                .andExpect(jsonPath("$.folders[?(@.name == 'Procédures')].inReviewCount")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.folders[?(@.name == 'Référence')].documentCount")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.folders[?(@.name == 'Référence')].inReviewCount")
                        .value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath("$.rootDocuments.length()").value(2))
                .andExpect(jsonPath("$.rootDocuments[?(@.status == 'brouillon')].title")
                        .value(org.hamcrest.Matchers.contains("Registre des accès à privilèges")))
                .andExpect(jsonPath("$.toReview.length()").value(1))
                .andExpect(jsonPath("$.toReview[0].title").value("Revue périodique des droits"))
                .andExpect(jsonPath("$.toReview[0].reviewSince").isNotEmpty())
                .andExpect(jsonPath("$.tags[?(@.name == 'IAM')]").isNotEmpty())
                .andReturn();
        BigDecimal average = new BigDecimal(MAPPER.readTree(result.getResponse().getContentAsString())
                .path("reliabilityAverage").asText());
        assertThat(average).isEqualByComparingTo("91");
        JsonNode folders = MAPPER.readTree(result.getResponse().getContentAsString()).path("folders");
        assertThat(folders).hasSize(2);
        BigDecimal proceduresAvg = new BigDecimal(folders.get(0).path("reliabilityAverage").asText());
        assertThat(proceduresAvg).isEqualByComparingTo("88");
    }

    private String createFolder(String auth, String space, String name) throws Exception {
        MvcResult folder = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":"%s","name":"%s"}
                                """.formatted(space, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return MAPPER.readTree(folder.getResponse().getContentAsString()).path("id").asText();
    }

    private void seedDoc(
            String auth, String space, String folder, String title, String docType,
            String docStatus, int reliability, Instant reviewSince
    ) throws Exception {
        String folderJson = folder == null ? "" : ",\"folderId\":\"" + folder + "\"";
        MvcResult created = mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","docType":"%s","spaceId":"%s"%s,"body":{"type":"doc","content":[{"type":"paragraph"}]}}
                                """.formatted(title, docType, space, folderJson)))
                .andExpect(status().isCreated())
                .andReturn();
        String docId = MAPPER.readTree(created.getResponse().getContentAsString()).path("id").asText();
        jdbc.update("""
                UPDATE documents SET status = ?, reliability_score = ? WHERE id = ?::uuid
                """, docStatus, reliability, docId);
        if ("IAM".equals(title) || title.contains("Provisioning") || title.contains("Rôles") || title.contains("Glossaire")) {
            mockMvc.perform(post("/api/v1/documents/" + docId + "/tags")
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"IAM\"}"))
                    .andExpect(status().is2xxSuccessful());
        }
        if (reviewSince != null) {
            UUID workflowId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO approval_workflows (id, name, status) VALUES (?, 'Revue maquette', 'active')",
                    workflowId);
            jdbc.update("""
                    INSERT INTO approval_requests
                      (id, document_id, workflow_id, temporal_workflow_id, requested_by, status, created_at)
                    VALUES (?, ?::uuid, ?, ?, ?, 'en_cours', ?)
                    """,
                    UUID.randomUUID(), docId, workflowId, "mockup-" + docId, CONTRIBUTOR,
                    Timestamp.from(reviewSince));
        }
    }

    private void putTogether(CountDownLatch start, String body, AtomicReference<MvcResult> out) {
        try {
            start.await();
            out.set(mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                            .header("Authorization", jwt(CONTRIBUTOR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createPage() throws Exception {
        MvcResult created = mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/page")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody(PAGE_TEXT)))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).path("id").asText();
    }

    private String createSibling() throws Exception {
        MvcResult sibling = mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Consigne","body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"frère"}]}]},"spaceId":"%s"}
                                """.formatted(spaceId)))
                .andExpect(status().isCreated())
                .andReturn();
        return MAPPER.readTree(sibling.getResponse().getContentAsString()).path("id").asText();
    }

    private String createReadPat() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/me/tokens")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"lecture\",\"scope\":\"read\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated())
                .andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).path("plaintext").asText();
    }

    private void archiveOld(String... documentIds) {
        for (String id : documentIds) {
            jdbc.update("""
                    UPDATE documents
                       SET status = 'archive', updated_at = now() - interval '8 years'
                     WHERE id = ?::uuid
                    """, id);
        }
    }

    private int countRows(String sql, Object id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private void assertPurgeQueueClean() throws Exception {
        for (int i = 0; i < 40; i++) {
            Integer failed = jdbc.queryForObject(
                    "SELECT count(*) FROM git_purge_queue WHERE status = 'failed'", Integer.class);
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM git_purge_queue WHERE status IN ('pending', 'running')", Integer.class);
            if ((failed == null || failed == 0) && (pending == null || pending == 0)) {
                return;
            }
            if (failed != null && failed > 0) {
                throw new AssertionError("purge Git en échec");
            }
            Thread.sleep(50);
        }
        throw new AssertionError("purge Git encore en cours");
    }

    private int pageCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documents d WHERE d.space_id = ? AND d.folder_id IS NULL AND EXISTS ("
                        + "SELECT 1 FROM spaces s WHERE s.page_document_id = d.id)",
                Integer.class, spaceId);
        return n == null ? 0 : n;
    }

    private void assertContentStored(UUID documentId, String text) throws Exception {
        if (documentStore instanceof GitDocumentStore) {
            Path file = Path.of(storageProperties.getGitRepositoryPath())
                    .resolve("documents").resolve(documentId + ".md");
            assertThat(file).isRegularFile();
            assertThat(Files.readString(file)).contains(text);
        } else {
            String body = jdbc.queryForObject("SELECT body::text FROM documents WHERE id = ?", String.class, documentId);
            assertThat(body).contains(text);
        }
    }

    private static String readZipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var entry = in.getNextEntry();
            while (entry != null) {
                if (name.equals(entry.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                entry = in.getNextEntry();
            }
        }
        throw new AssertionError("entrée absente : " + name);
    }

    private static String pageBody(String text) {
        return """
                {"body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(text);
    }

    private JsonNode awaitAudit(String action) throws Exception {
        for (int i = 0; i < 80; i++) {
            List<String> rows = jdbc.queryForList(
                    "SELECT metadata::text FROM audit_log_events WHERE action = ? ORDER BY id DESC",
                    String.class, action);
            if (!rows.isEmpty()) {
                return MAPPER.readTree(rows.getFirst());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("audit absent : " + action);
    }

    private void name(UUID id, String displayName, String initials) {
        jdbc.update(
                "UPDATE users SET display_name = ?, avatar_initials = ? WHERE id = ?",
                displayName, initials, id);
    }

    private void seed(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, last_login_at)
                VALUES (?, ?, ?, 'active', now())
                """, id, email, email);
        jdbc.update("""
                INSERT INTO user_identities (user_id, issuer, subject, linked_at)
                VALUES (?, ?, ?, now())
                """, id, ISSUER, id.toString());
    }

    private void stubFga() throws Exception {
        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(empty));
        ClientListUsersResponse listUsers = mock(ClientListUsersResponse.class);
        when(listUsers.getUsers()).thenReturn(List.of());
        when(openFgaClient.listUsers(any())).thenReturn(CompletableFuture.completedFuture(listUsers));
        when(openFgaClient.check(any(ClientCheckRequest.class))).thenAnswer(inv -> {
            ClientCheckRequest req = inv.getArgument(0);
            ClientCheckResponse response = mock(ClientCheckResponse.class);
            when(response.getAllowed()).thenReturn(allowed(req.getUser(), req.getRelation()));
            return CompletableFuture.completedFuture(response);
        });
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            ClientBatchCheckRequest req = inv.getArgument(0);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                results.add(new ClientBatchCheckSingleResponse(
                        allowed(item.getUser(), item.getRelation()), item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });
        when(openFgaClient.write(any(), any())).thenReturn(
                CompletableFuture.completedFuture(mock(ClientWriteResponse.class)));
    }

    private static boolean allowed(String user, String relation) {
        if (user == null) {
            return false;
        }
        if (user.endsWith(CONTRIBUTOR.toString())) {
            return true;
        }
        if (user.endsWith(READER.toString())) {
            return "viewer".equals(relation);
        }
        return false;
    }

    static String jwt(UUID userId) throws Exception {
        return jwt(userId, "Test");
    }

    static String jwt(UUID userId, String name) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", userId + "@example.com")
                .claim("email_verified", true)
                .claim("name", name)
                .claim("realm_access", Map.of("roles", List.of("contributeur")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return "Bearer " + jwt.serialize();
    }

    @SuppressWarnings("unused")
    private static final Class<?> APP = SocleBackendApplication.class;
}
