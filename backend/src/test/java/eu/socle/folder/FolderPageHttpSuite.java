// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page de dossier, exécutée deux fois (relational et git) par les sous-classes.
 */
abstract class FolderPageHttpSuite {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final UUID CONTRIBUTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID READER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String PAGE_TEXT = "Bonjour dossier";

    static RSAKey rsaKey;

    static {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("folder-page-test").generate();
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

    @MockBean WorkflowClient workflowClient;
    @MockBean OpenFgaClient openFgaClient;

    UUID spaceId;
    UUID folderId;
    UUID otherFolderId;

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
            jdbc.update("DELETE FROM documents");
            jdbc.update("DELETE FROM folders");
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
        otherFolderId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spaces (id, name, default_visibility)
                VALUES (?, 'Espace procédures', 'organisation')
                """, spaceId);
        jdbc.update("""
                INSERT INTO groups (id, name, created_by, created_at)
                VALUES (?, 'Équipe doc', ?, now())
                """, groupId, CONTRIBUTOR);
        jdbc.update("""
                INSERT INTO folders (id, space_id, name, created_by, status, owner_group_id)
                VALUES (?, ?, 'Procédures', ?, 'active', ?)
                """, folderId, spaceId, CONTRIBUTOR, groupId);
        jdbc.update("""
                INSERT INTO folders (id, space_id, name, created_by, status)
                VALUES (?, ?, 'Archives', ?, 'active')
                """, otherFolderId, spaceId, CONTRIBUTOR);
    }

    @Test
    void get_beforeWrite_is204_andNoDocument() throws Exception {
        mockMvc.perform(get("/api/v1/folders/" + folderId + "/page").header("Authorization", jwt(CONTRIBUTOR)))
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
                "SELECT page_document_id::text FROM folders WHERE id = ?", String.class, folderId))
                .isEqualTo(id1);
        int v1 = MAPPER.readTree(first.get().getResponse().getContentAsString()).path("currentVersionNo").asInt();
        int v2 = MAPPER.readTree(second.get().getResponse().getContentAsString()).path("currentVersionNo").asInt();
        assertThat(List.of(v1, v2)).containsExactlyInAnyOrder(1, 2);

        JsonNode created = awaitAudit(AuditActions.DOCUMENT_CREATED);
        assertThat(created.path("folderPage").asBoolean()).isTrue();
        assertThat(created.path("folderId").asText()).isEqualTo(folderId.toString());
        JsonNode updated = awaitAudit(AuditActions.DOCUMENT_UPDATED);
        assertThat(updated.path("folderPage").asBoolean()).isTrue();
        assertThat(updated.path("folderId").asText()).isEqualTo(folderId.toString());
        assertContentStored(UUID.fromString(id1), PAGE_TEXT);
    }

    @Test
    void listsExcludeThePage_searchLabelsIt() throws Exception {
        String pageId = createPage();
        MvcResult sibling = mockMvc.perform(post("/api/v1/documents")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Consigne","body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Consigne frère"}]}]},"spaceId":"%s","folderId":"%s"}
                                """.formatted(spaceId, folderId)))
                .andExpect(status().isCreated())
                .andReturn();
        String siblingId = MAPPER.readTree(sibling.getResponse().getContentAsString()).path("id").asText();
        jdbc.update("UPDATE documents SET status = 'valide', reliability_score = 100 WHERE id = ?::uuid", siblingId);
        jdbc.update("UPDATE documents SET reliability_score = 0 WHERE id = ?::uuid", pageId);

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/tree").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[?(@.id == '" + folderId + "')].documentCount")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.folders[?(@.id == '" + folderId + "')].documents[?(@.id == '" + pageId + "')]").isEmpty())
                .andExpect(jsonPath("$.folders[?(@.id == '" + folderId + "')].documents[?(@.id == '" + siblingId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/folders/" + folderId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.pageDocumentId").value(pageId))
                .andExpect(jsonPath("$.status").value("actif"))
                .andExpect(jsonPath("$.creator.kind").value("user"))
                .andExpect(jsonPath("$.creator.id").value(CONTRIBUTOR.toString()))
                .andExpect(jsonPath("$.ownerGroup.name").value("Équipe doc"))
                .andExpect(jsonPath("$.statusCounts.validated").value(1))
                .andExpect(jsonPath("$.statusCounts.draft").value(0))
                .andExpect(jsonPath("$.reliabilityAverage").value(100));

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
                        .value(org.hamcrest.Matchers.contains("Dossier — Procédures")))
                .andExpect(jsonPath("$.results[?(@.id == '" + pageId + "')].folderId")
                        .value(org.hamcrest.Matchers.contains(folderId.toString())));
    }

    @Test
    void pageAlone_isLocked_folderCarriesIt() throws Exception {
        String pageId = createPage();

        mockMvc.perform(post("/api/v1/documents/" + pageId + "/move")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + otherFolderId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.FOLDER_PAGE_LOCKED));
        mockMvc.perform(delete("/api/v1/trash/documents/" + pageId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.FOLDER_PAGE_LOCKED));

        mockMvc.perform(delete("/api/v1/folders/" + folderId).header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();
        UUID docTrash = jdbc.queryForObject(
                "SELECT id FROM trash_items WHERE resource_type = 'document' AND resource_id = ?::uuid",
                UUID.class, pageId);
        mockMvc.perform(post("/api/v1/trash/" + docTrash + "/restore").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiErrors.FOLDER_PAGE_LOCKED));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();

        UUID folderTrash = jdbc.queryForObject(
                "SELECT id FROM trash_items WHERE resource_type = 'folder' AND resource_id = ?",
                UUID.class, folderId);
        mockMvc.perform(post("/api/v1/trash/" + folderTrash + "/restore").header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM documents WHERE id = ?::uuid", Boolean.class, pageId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT folder_id FROM documents WHERE id = ?::uuid", UUID.class, pageId))
                .isEqualTo(folderId);

        mockMvc.perform(post("/api/v1/folders/" + folderId + "/move")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentFolderId\":\"" + otherFolderId + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT folder_id FROM documents WHERE id = ?::uuid", UUID.class, pageId))
                .isEqualTo(folderId);
        assertThat(jdbc.queryForObject("SELECT parent_folder_id FROM folders WHERE id = ?", UUID.class, folderId))
                .isEqualTo(otherFolderId);
    }

    @Test
    void rename_syncsPageTitle() throws Exception {
        String pageId = createPage();
        mockMvc.perform(patch("/api/v1/folders/" + folderId)
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

        mockMvc.perform(get("/api/v1/folders/" + folderId + "/page").header("Authorization", jwt(READER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pageId));
        mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                        .header("Authorization", jwt(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("interdit")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/folders/" + folderId + "/page").header("Authorization", jwt(STRANGER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                        .header("Authorization", jwt(STRANGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("interdit")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("par le contributeur")))
                .andExpect(status().isOk());

        String pat = createReadPat();
        mockMvc.perform(get("/api/v1/folders/" + folderId + "/page").header("Authorization", "Bearer " + pat))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                        .header("Authorization", "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("pat")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiErrors.PAT_SCOPE_INSUFFICIENT));
    }

    @Test
    void zipExport_includesFolderPageAtRoot() throws Exception {
        createPage();
        MvcResult zip = mockMvc.perform(get("/api/v1/folders/" + folderId + "/export")
                        .param("format", "zip")
                        .header("Authorization", jwt(CONTRIBUTOR)))
                .andExpect(status().isOk())
                .andReturn();
        String markdown = readZipEntry(zip.getResponse().getContentAsByteArray(), "_dossier.md");
        assertThat(markdown).contains(PAGE_TEXT);
        assertThat(storageProperties.getProvider()).isIn("relational", "git");
    }

    private void putTogether(CountDownLatch start, String body, AtomicReference<MvcResult> out) {
        try {
            start.await();
            out.set(mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                            .header("Authorization", jwt(CONTRIBUTOR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createPage() throws Exception {
        MvcResult created = mockMvc.perform(put("/api/v1/folders/" + folderId + "/page")
                        .header("Authorization", jwt(CONTRIBUTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody(PAGE_TEXT)))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(created.getResponse().getContentAsString()).path("id").asText();
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

    private int pageCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documents d WHERE d.folder_id = ? AND EXISTS ("
                        + "SELECT 1 FROM folders f WHERE f.page_document_id = d.id)",
                Integer.class, folderId);
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
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", userId + "@example.com")
                .claim("email_verified", true)
                .claim("name", "Test")
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
