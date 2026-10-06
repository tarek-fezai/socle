// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.export;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.TransclusionResolver;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.DocumentStore;
import eu.socle.storage.GitDocumentStore;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportServiceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID PAGE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID TARGET_OK = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID TARGET_SECRET = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC_VISIBLE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID DOC_HIDDEN = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    static final UUID FOLDER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static final UUID TAG = UUID.fromString("22345678-1234-1234-1234-123456789abc");

    @Mock DocumentRepository documentRepository;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;
    @Mock ExternalReferencePolicy externalReferencePolicy;
    @Mock ExternalReferenceNotify externalReferenceNotify;
    @Mock JdbcTemplate jdbc;

    DocumentStore documentStore;
    TransclusionResolver resolver;
    ExportService exportService;

    @BeforeEach
    void setUp() {
        documentStore = new RelationalDocumentStore(mock(DocumentVersionRepository.class));
        when(externalReferencePolicy.allowsInterWorkspaceEdge(any(), any())).thenReturn(true);
        resolver = new TransclusionResolver(
                documentRepository, documentStore, authorizationService,
                externalReferencePolicy, externalReferenceNotify);
        exportService = new ExportService(
                documentRepository, documentStore, resolver,
                authorizationService, userSyncService, auditService, jdbc);

        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        doNothing().when(authorizationService).requireDocumentRelation(USER, PAGE, "viewer");
        doNothing().when(authorizationService).requireFolderRelation(USER, FOLDER, "viewer");
    }

    @Test
    void exportDocument_withoutViewerOnTarget_returns403WithoutGeneratingPdf() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (viewer)"))
                .when(authorizationService).requireDocumentRelation(USER, PAGE, "viewer");

        assertThatThrownBy(() -> exportService.exportDocument(jwt(), PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));

        verify(documentRepository, never()).findActiveById(any());
        verify(auditService, never()).record(any(), any(Boolean.class), any(), any(), any(), any(), any());
    }

    @Test
    void exportDocument_accessibleTransclusion_includesResolvedContent() {
        DocumentEntity page = entity(PAGE, "Composite", compositeBody(TARGET_OK));
        DocumentEntity target = entity(TARGET_OK, "Procedure publique", tipTap("Texte resolu visible"));
        when(documentRepository.findActiveById(PAGE)).thenReturn(Optional.of(page));
        when(documentRepository.findActiveById(TARGET_OK)).thenReturn(Optional.of(target));
        when(authorizationService.hasRelation(USER, "document", TARGET_OK, "viewer")).thenReturn(true);

        ExportService.ExportFile file = exportService.exportDocument(jwt(), PAGE);

        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.bytes()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        String text = TipTapPdfRenderer.extractText(file.bytes());
        assertThat(text).contains("Texte resolu visible");
        assertThat(text).contains("Composite");

        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_EXPORTED),
                eq("document"), eq(PAGE), any(), isNull());
    }

    @Test
    void exportDocument_forbiddenTransclusion_showsIndicatorWithoutLeak() {
        DocumentEntity page = entity(PAGE, "Composite", compositeBody(TARGET_SECRET));
        DocumentEntity secret = entity(TARGET_SECRET, "Titre confidentiel", tipTap("Corps secret interdit"));
        when(documentRepository.findActiveById(PAGE)).thenReturn(Optional.of(page));
        when(documentRepository.findActiveById(TARGET_SECRET)).thenReturn(Optional.of(secret));
        when(authorizationService.hasRelation(USER, "document", TARGET_SECRET, "viewer")).thenReturn(false);

        ExportService.ExportFile file = exportService.exportDocument(jwt(), PAGE);

        String text = TipTapPdfRenderer.extractText(file.bytes());
        assertThat(text).contains(TipTapPdfRenderer.INACCESSIBLE_LABEL);
        assertThat(text).doesNotContain("Titre confidentiel");
        assertThat(text).doesNotContain("Corps secret interdit");
        assertThat(text).doesNotContain(TARGET_SECRET.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportFolder_hidesUnreadableDocuments() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(FOLDER))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            RowMapper<Object> mapper = inv.getArgument(1);
            if (sql.contains("FROM folders WHERE id")) {
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("id")).thenReturn(FOLDER);
                when(rs.getString("name")).thenReturn("Procedures");
                return List.of(mapper.mapRow(rs, 0));
            }
            if (sql.contains("WITH RECURSIVE")) {
                return List.of(FOLDER);
            }
            return List.of();
        });
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(DOC_VISIBLE, DOC_HIDDEN));

        DocumentEntity visible = entity(DOC_VISIBLE, "Doc visible", tipTap("Contenu A"));
        DocumentEntity hidden = entity(DOC_HIDDEN, "Doc secret dossier", tipTap("Fuite interdite"));
        when(documentRepository.findAllActiveByIdIn(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var ids = new java.util.HashSet<>((java.util.Collection<UUID>) inv.getArgument(0));
            List<DocumentEntity> out = new java.util.ArrayList<>();
            if (ids.contains(DOC_VISIBLE)) {
                out.add(visible);
            }
            if (ids.contains(DOC_HIDDEN)) {
                out.add(hidden);
            }
            return out;
        });
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenReturn(List.of(DOC_VISIBLE));
        when(documentRepository.findActiveById(DOC_VISIBLE)).thenReturn(Optional.of(visible));

        ExportService.ExportFile file = exportService.exportFolder(jwt(), FOLDER);

        String text = TipTapPdfRenderer.extractText(file.bytes());
        assertThat(text).contains("Doc visible");
        assertThat(text).contains("Contenu A");
        assertThat(text).doesNotContain("Doc secret dossier");
        assertThat(text).doesNotContain("Fuite interdite");
        assertThat(text).doesNotContain(DOC_HIDDEN.toString());

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.FOLDER_EXPORTED),
                eq("folder"), eq(FOLDER), meta.capture(), isNull());
        assertThat(meta.getValue().get("documentCount")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportTag_hidesUnreadableDocuments() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(TAG))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            RowMapper<Object> mapper = inv.getArgument(1);
            if (sql.contains("FROM tags")) {
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getObject("id")).thenReturn(TAG);
                when(rs.getString("name")).thenReturn("iso27001");
                return List.of(mapper.mapRow(rs, 0));
            }
            if (sql.contains("document_tags")) {
                return List.of(DOC_VISIBLE, DOC_HIDDEN);
            }
            return List.of();
        });

        DocumentEntity visible = entity(DOC_VISIBLE, "Tagge visible", tipTap("OK"));
        DocumentEntity hidden = entity(DOC_HIDDEN, "Tagge secret", tipTap("NON"));
        when(documentRepository.findAllActiveByIdIn(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var ids = new java.util.HashSet<>((java.util.Collection<UUID>) inv.getArgument(0));
            List<DocumentEntity> out = new java.util.ArrayList<>();
            if (ids.contains(DOC_VISIBLE)) {
                out.add(visible);
            }
            if (ids.contains(DOC_HIDDEN)) {
                out.add(hidden);
            }
            return out;
        });
        when(authorizationService.listViewableDocumentIds(eq(USER), any())).thenReturn(List.of(DOC_VISIBLE));
        when(documentRepository.findActiveById(DOC_VISIBLE)).thenReturn(Optional.of(visible));

        ExportService.ExportFile file = exportService.exportTag(jwt(), TAG);

        String text = TipTapPdfRenderer.extractText(file.bytes());
        assertThat(text).contains("Tagge visible");
        assertThat(text).doesNotContain("Tagge secret");
        assertThat(text).doesNotContain("NON");
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.TAG_EXPORTED),
                eq("tag"), eq(TAG), any(), isNull());
    }

    @Test
    void relationalAndGit_sameResolvedContent_samePdfText(@TempDir Path tempDir) {
        DocumentEntity page = entity(PAGE, "Même titre", tipTap("Corps dual-provider"));
        when(documentRepository.findActiveById(PAGE)).thenReturn(Optional.of(page));

        RelationalDocumentStore rel = new RelationalDocumentStore(mock(DocumentVersionRepository.class));
        GitDocumentStore git = new GitDocumentStore(
                mock(DocumentVersionRepository.class), tempDir.resolve("repo"));
        git.createContent(PAGE, new HashMap<>(tipTap("Corps dual-provider")), USER);

        TransclusionResolver relResolver = new TransclusionResolver(
                documentRepository, rel, authorizationService,
                externalReferencePolicy, externalReferenceNotify);
        TransclusionResolver gitResolver = new TransclusionResolver(
                documentRepository, git, authorizationService,
                externalReferencePolicy, externalReferenceNotify);

        Map<String, Object> relResolved = relResolver.resolve(
                USER, PAGE, rel.readCurrentContent(PAGE, page.getBody()));
        Map<String, Object> gitResolved = gitResolver.resolve(
                USER, PAGE, git.readCurrentContent(PAGE, page.getBody()));

        assertThat(TipTapPdfRenderer.toPlainText(page.getTitle(), relResolved))
                .isEqualTo(TipTapPdfRenderer.toPlainText(page.getTitle(), gitResolved))
                .contains("Corps dual-provider");
    }

    private static DocumentEntity entity(UUID id, String title, Map<String, Object> body) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setSpaceId(SPACE);
        d.setTitle(title);
        d.setBody(body);
        d.setStatus("brouillon");
        d.setCurrentVersionNo(1);
        try {
            var c = DocumentEntity.class.getDeclaredField("createdAt");
            c.setAccessible(true);
            c.set(d, Instant.now());
            var u = DocumentEntity.class.getDeclaredField("updatedAt");
            u.setAccessible(true);
            u.set(d, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    private static Map<String, Object> tipTap(String text) {
        return Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text)))));
    }

    private static Map<String, Object> compositeBody(UUID targetId) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "transclusion");
        block.put("attrs", Map.of("documentId", targetId.toString()));
        return Map.of("type", "doc", "content", List.of(block));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
