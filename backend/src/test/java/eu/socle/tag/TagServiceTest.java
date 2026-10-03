// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.TagRef;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Étiquettes sur PostgreSQL réel (schéma V1 minimal) ; authz / identité / audit mockés. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TRASHED = UUID.fromString("11111111-1111-1111-1111-111111111112");
    static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;

    TagService service;
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
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name TEXT NOT NULL UNIQUE, color TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                  PRIMARY KEY (document_id, tag_id)
                )
                """);
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, 'Doc')", DOC);
        jdbc.update("INSERT INTO documents (id, title, deleted_at) VALUES (?, 'Corbeille', now())", TRASHED);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_tags");
        jdbc.update("DELETE FROM tags");
        service = new TagService(jdbc, userSyncService, authorizationService, auditService);
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
    }

    private UUID insertTag(String name, String color) {
        return jdbc.queryForObject(
                "INSERT INTO tags (name, color) VALUES (?, ?) RETURNING id", UUID.class, name, color);
    }

    private List<String> docTagNames(UUID doc) {
        return jdbc.queryForList("""
                SELECT t.name FROM document_tags dt JOIN tags t ON t.id = dt.tag_id
                 WHERE dt.document_id = ? ORDER BY t.name
                """, String.class, doc);
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(expected);
    }

    // ---------- GET /tags?q= ----------

    @Test
    void search_matchesSubstringCaseInsensitive_prefixFirst_andReturnsColor() {
        insertTag("RGPD", "#ff0000");
        insertTag("Critique", null);
        insertTag("Pré-RGPD", "#00ff00");

        List<TagRef> found = service.search(jwt, "rgp", null);

        assertThat(found).extracting(TagRef::name).containsExactly("RGPD", "Pré-RGPD");
        assertThat(found.getFirst().color()).isEqualTo("#ff0000");
        assertThat(found.getFirst().id()).isNotNull();
    }

    @Test
    void search_emptyQuery_listsAll_and_escapesLikeWildcards() {
        insertTag("IAM", null);
        insertTag("100%", null);

        assertThat(service.search(jwt, null, null)).hasSize(2);
        assertThat(service.search(jwt, "%", null)).extracting(TagRef::name).containsExactly("100%");
        assertThat(service.search(jwt, "_", null)).isEmpty();
    }

    @Test
    void search_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            insertTag("t" + i, null);
        }
        assertThat(service.search(jwt, "t", 2)).hasSize(2);
    }

    // ---------- POST /documents/{id}/tags ----------

    @Test
    void attachByNewName_createsTag_attaches_andAudits() {
        TagService.Attachment a = service.attach(jwt, DOC, null, "  Conformité  ");

        assertThat(a.created()).isTrue();
        assertThat(a.tag().name()).isEqualTo("Conformité");
        assertThat(docTagNames(DOC)).containsExactly("Conformité");
        verify(authorizationService).requireDocumentRelation(USER, DOC, "editor");
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_TAG_ADDED), eq("document"), eq(DOC),
                anyMap(), any());
    }

    @Test
    void attachByName_reusesExistingTag_caseInsensitive_noDuplicate() {
        UUID existing = insertTag("RGPD", "#f00");

        TagService.Attachment a = service.attach(jwt, DOC, null, "rgpd");

        assertThat(a.tag().id()).isEqualTo(existing);
        assertThat(a.tag().color()).isEqualTo("#f00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isEqualTo(1);
    }

    @Test
    void attachByTagId_isIdempotent_andAuditsOnce() {
        UUID tag = insertTag("IAM", null);

        assertThat(service.attach(jwt, DOC, tag, null).created()).isTrue();
        assertThat(service.attach(jwt, DOC, tag, null).created()).isFalse();

        assertThat(docTagNames(DOC)).containsExactly("IAM");
        verify(auditService, org.mockito.Mockito.times(1)).record(
                any(), eq(false), eq(AuditActions.DOCUMENT_TAG_ADDED), any(), any(), anyMap(), any());
    }

    @Test
    void attach_requiresExactlyOneOfTagIdOrName() {
        UUID tag = insertTag("IAM", null);
        assertThatThrownBy(() -> service.attach(jwt, DOC, null, null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.attach(jwt, DOC, tag, "IAM"))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.attach(jwt, DOC, null, "   "))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.attach(jwt, DOC, null, "x".repeat(TagService.MAX_NAME_LENGTH + 1)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void attachUnknownTagId_is404() {
        assertThatThrownBy(() -> service.attach(jwt, DOC, UUID.randomUUID(), null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void attach_withoutEditRight_is403_andWritesNothing() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        assertThatThrownBy(() -> service.attach(jwt, DOC, null, "Secret"))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isZero();
        assertThat(docTagNames(DOC)).isEmpty();
        verify(auditService, never()).record(any(), eq(false), any(), any(), any(), any(), any());
    }

    @Test
    void attach_unknownOrTrashedDocument_is404_beforeAnyAuthzCheck() {
        assertThatThrownBy(() -> service.attach(jwt, UNKNOWN, null, "X"))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.attach(jwt, TRASHED, null, "X"))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        verify(authorizationService, never()).requireDocumentRelation(any(), any(), any());
    }

    // ---------- DELETE /documents/{id}/tags/{tagId} ----------

    @Test
    void detach_removesLink_keepsTag_andAudits() {
        UUID tag = insertTag("IAM", null);
        service.attach(jwt, DOC, tag, null);

        service.detach(jwt, DOC, tag);

        assertThat(docTagNames(DOC)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isEqualTo(1);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_TAG_REMOVED), eq("document"), eq(DOC),
                anyMap(), any());
    }

    @Test
    void detach_notAttachedOrUnknownTag_is404() {
        UUID tag = insertTag("IAM", null);
        assertThatThrownBy(() -> service.detach(jwt, DOC, tag))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.detach(jwt, DOC, UUID.randomUUID()))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void detach_withoutEditRight_is403_andKeepsLink() {
        UUID tag = insertTag("IAM", null);
        service.attach(jwt, DOC, tag, null);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        assertThatThrownBy(() -> service.detach(jwt, DOC, tag))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));

        assertThat(docTagNames(DOC)).containsExactly("IAM");
    }

    @Test
    void detach_unknownDocument_is404() {
        assertThatThrownBy(() -> service.detach(jwt, UNKNOWN, UUID.randomUUID()))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }
}
