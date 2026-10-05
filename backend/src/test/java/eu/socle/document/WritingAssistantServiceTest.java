// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.WritingAssistantService.Hints;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Assistant d'écriture : liens cassés (PostgreSQL réel, authz mockée) + paragraphes longs. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WritingAssistantServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID VISIBLE = UUID.fromString("22222222-2222-2222-2222-2222222222a1");
    static final UUID DENIED = UUID.fromString("22222222-2222-2222-2222-2222222222a2");
    static final UUID DELETED = UUID.fromString("22222222-2222-2222-2222-2222222222a3");
    static final UUID MISSING = UUID.fromString("22222222-2222-2222-2222-2222222222a4");
    static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    static final String ANCHOR_DENIED = "voir la procédure RH";
    static final String ANCHOR_DELETED = "Procédure de provisioning v9";
    static final String SECRET_DENIED_TITLE = "Titre ultra secret";
    static final String SECRET_DELETED_TITLE = "Titre supprimé secret";

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentStore documentStore;

    WritingAssistantProperties properties;
    WritingAssistantService service;
    DocumentEntity entity;
    Jwt jwt;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_links (
                  source_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  target_id UUID NOT NULL,
                  source_space_id UUID NOT NULL,
                  link_type TEXT NOT NULL DEFAULT 'transclusion',
                  PRIMARY KEY (source_id, target_id, link_type)
                )
                """);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        insertDoc(DOC, "Courant", false);
        insertDoc(VISIBLE, "Cible visible", false);
        insertDoc(DENIED, SECRET_DENIED_TITLE, false);
        insertDoc(DELETED, SECRET_DELETED_TITLE, true);
        link(DOC, VISIBLE);
        link(DOC, DENIED);
        link(DOC, DELETED);
        link(DOC, MISSING);
        link(DOC, DOC); // auto-référence ignorée
    }

    static void insertDoc(UUID id, String title, boolean deleted) {
        jdbc.update("INSERT INTO documents (id, space_id, title, deleted_at) VALUES (?, ?, ?, "
                + (deleted ? "now()" : "NULL") + ")", id, SPACE, title);
    }

    static void link(UUID source, UUID target) {
        jdbc.update("INSERT INTO document_links (source_id, target_id, source_space_id) VALUES (?, ?, ?)",
                source, target, SPACE);
    }

    @BeforeEach
    void setUp() {
        properties = new WritingAssistantProperties();
        service = new WritingAssistantService(
                jdbc, userSyncService, authorizationService, documentRepository, documentStore, properties);
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();

        entity = new DocumentEntity();
        entity.setBody(bodyWithAnchors());
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(documentRepository.findActiveById(UNKNOWN)).thenReturn(Optional.empty());
        when(documentStore.readCurrentContent(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(authorizationService.filterByDocumentViewer(eq(USER), anyCollection(), any()))
                .thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(1)).stream()
                        .filter(id -> Set.of(VISIBLE).contains(id)).toList());
    }

    /** Corps source avec textes d'ancre vers les cibles indexées (pas les titres cibles). */
    static Map<String, Object> bodyWithAnchors() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "doc");
        doc.put("content", List.of(
                paragraphWithLink(ANCHOR_DENIED, DENIED),
                paragraphWithLink(ANCHOR_DELETED, DELETED),
                Map.of("type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", "Court.")))));
        return doc;
    }

    static Map<String, Object> paragraphWithLink(String anchor, UUID targetId) {
        Map<String, Object> text = new HashMap<>();
        text.put("type", "text");
        text.put("text", anchor);
        text.put("marks", List.of(Map.of(
                "type", "link",
                "attrs", Map.of("href", "/docs/" + targetId))));
        return Map.of("type", "paragraph", "content", List.of(text));
    }

    static Map<String, Object> paragraphs(String... texts) {
        List<Object> content = new java.util.ArrayList<>();
        for (String t : texts) {
            content.add(Map.of("type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", t))));
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "doc");
        doc.put("content", content);
        return doc;
    }

    static Map<String, Object> node(String type, Object... children) {
        Map<String, Object> n = new HashMap<>();
        n.put("type", type);
        if (children.length == 1 && children[0] instanceof String text && "text".equals(type)) {
            n.put("text", text);
        } else if (children.length > 0) {
            n.put("content", List.of(children));
        }
        return n;
    }

    static String words(int n) {
        return String.join(" ", java.util.Collections.nCopies(n, "mot"));
    }

    // ---------- liens cassés ----------

    @Test
    void inaccessibleTarget_usesSourceAnchor_andNeverLeaksTitle() throws Exception {
        Hints hints = service.hints(jwt, DOC);

        assertThat(hints.brokenLinks()).extracting(WritingAssistantService.BrokenLink::targetId)
                .containsExactlyInAnyOrder(DENIED, DELETED, MISSING)
                .doesNotContain(VISIBLE, DOC);

        var denied = hints.brokenLinks().stream()
                .filter(l -> DENIED.equals(l.targetId())).findFirst().orElseThrow();
        assertThat(denied.label()).isEqualTo(ANCHOR_DENIED);
        assertThat(denied.reason()).isEqualTo(WritingAssistantService.REASON_INACCESSIBLE);
        assertThat(denied.accessible()).isFalse();

        var deleted = hints.brokenLinks().stream()
                .filter(l -> DELETED.equals(l.targetId())).findFirst().orElseThrow();
        assertThat(deleted.label()).isEqualTo(ANCHOR_DELETED);
        assertThat(deleted.reason()).isEqualTo(WritingAssistantService.REASON_DELETED);
        assertThat(deleted.accessible()).isFalse();

        var missing = hints.brokenLinks().stream()
                .filter(l -> MISSING.equals(l.targetId())).findFirst().orElseThrow();
        assertThat(missing.label()).isEqualTo(WritingAssistantService.INACCESSIBLE_LABEL);
        assertThat(missing.reason()).isEqualTo(WritingAssistantService.REASON_DELETED);

        String json = MAPPER.writeValueAsString(hints);
        assertThat(json)
                .doesNotContain("secret")
                .doesNotContain(SECRET_DENIED_TITLE)
                .doesNotContain(SECRET_DELETED_TITLE)
                .doesNotContain("Cible visible")
                .contains(ANCHOR_DENIED)
                .contains(ANCHOR_DELETED);
    }

    @Test
    void withoutAnchorText_fallsBackToFixedLabel() {
        entity.setBody(paragraphs("Aucun lien inline."));
        Hints hints = service.hints(jwt, DOC);
        assertThat(hints.brokenLinks()).allSatisfy(l -> {
            assertThat(l.label()).isEqualTo(WritingAssistantService.INACCESSIBLE_LABEL);
            assertThat(l.accessible()).isFalse();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchCheck_isOnlyDoneOnLiveTargets_inOneCall() {
        service.hints(jwt, DOC);

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(authorizationService).filterByDocumentViewer(eq(USER), captor.capture(), any());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(VISIBLE, DENIED)
                .doesNotContain(DELETED, MISSING, DOC);
    }

    @Test
    void documentWithoutLinks_hasEmptyHints_andDefaultThreshold120() {
        UUID lonely = UUID.fromString("99999999-9999-9999-9999-999999999998");
        insertDoc(lonely, "Seul", false);
        DocumentEntity e = new DocumentEntity();
        e.setBody(paragraphs("Court."));
        when(documentRepository.findActiveById(lonely)).thenReturn(Optional.of(e));

        Hints hints = service.hints(jwt, lonely);

        assertThat(hints.brokenLinks()).isEmpty();
        assertThat(hints.longParagraphs()).isEmpty();
        assertThat(hints.longParagraphThresholdWords()).isEqualTo(120);
        verify(authorizationService, never()).filterByDocumentViewer(any(), anyCollection(), any());
    }

    @Test
    void withoutViewerRight_is403_andNothingIsComputed() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (viewer)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "viewer");

        assertThatThrownBy(() -> service.hints(jwt, DOC))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).filterByDocumentViewer(any(), anyCollection(), any());
    }

    @Test
    void unknownDocument_is404() {
        assertThatThrownBy(() -> service.hints(jwt, UNKNOWN))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------- paragraphes longs ----------

    @Test
    void longParagraphs_useConfiguredThreshold_fromCurrentBody() {
        entity.setBody(paragraphs("Court.", words(121), words(120)));

        Hints hints = service.hints(jwt, DOC);
        assertThat(hints.longParagraphs()).hasSize(1);
        assertThat(hints.longParagraphs().getFirst().index()).isEqualTo(1);
        assertThat(hints.longParagraphs().getFirst().wordCount()).isEqualTo(121);

        properties.setLongParagraphWords(5);
        Hints strict = service.hints(jwt, DOC);
        assertThat(strict.longParagraphThresholdWords()).isEqualTo(5);
        assertThat(strict.longParagraphs()).extracting(WritingAssistantService.LongParagraph::index)
                .containsExactly(1, 2);
    }

    @Test
    void longParagraphs_walkNestedNodes_skipTransclusion_andJoinInlineMarks() {
        Object paragraph = node("paragraph",
                node("text", "un deux "),
                node("text", "trois"),
                node("hardBreak"),
                node("text", "quatre"));
        Map<String, Object> body = node("doc",
                node("bulletList", node("listItem", paragraph)),
                node("transclusion"));

        var found = WritingAssistantService.longParagraphs(body, 3);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().wordCount()).isEqualTo(4);
        assertThat(found.getFirst().excerpt()).isEqualTo("un deux trois quatre");
    }

    @Test
    void longParagraphs_nullOrEmptyBody_isEmpty() {
        assertThat(WritingAssistantService.longParagraphs(null, 10)).isEmpty();
        assertThat(WritingAssistantService.longParagraphs(Map.of(), 10)).isEmpty();
    }

    @Test
    void properties_rejectNonPositiveThreshold() {
        var p = new WritingAssistantProperties();
        p.setLongParagraphWords(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }
}
