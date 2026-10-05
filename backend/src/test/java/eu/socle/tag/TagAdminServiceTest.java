// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.document.ApprovalRoleResolver;
import eu.socle.identity.IdentityFacade;
import eu.socle.tag.TagAdminDtos.CreateTagRequest;
import eu.socle.tag.TagAdminDtos.MergeTagRequest;
import eu.socle.tag.TagAdminDtos.RenameTagRequest;
import eu.socle.tag.TagAdminDtos.TagAdminView;
import eu.socle.tag.TagAdminDtos.TagCreationPolicyRequest;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagAdminServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID ROLE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID APPROVER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    TagAdminService service;
    TagService tagService;
    ApprovalRoleResolver roleResolver;
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
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT, display_name TEXT NOT NULL, status TEXT DEFAULT 'active'
                )
                """);
        jdbc.execute("""
                CREATE TABLE groups (id UUID PRIMARY KEY, name TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL REFERENCES groups(id),
                  user_id UUID NOT NULL REFERENCES users(id),
                  PRIMARY KEY (group_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE global_roles (id UUID PRIMARY KEY, name TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL, space_id UUID, deleted_at TIMESTAMPTZ, doc_type TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE tags (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  name TEXT NOT NULL UNIQUE,
                  color TEXT,
                  created_by UUID REFERENCES users(id),
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uq_tags_name_ci ON tags (lower(name))");
        jdbc.execute("""
                CREATE TABLE document_tags (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                  PRIMARY KEY (document_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_role_assignments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  role_id UUID NOT NULL REFERENCES global_roles(id),
                  subject_type TEXT NOT NULL,
                  subject_id UUID NOT NULL,
                  scope_type TEXT NOT NULL,
                  scope_ref TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id),
                  status TEXT NOT NULL DEFAULT 'en_cours'
                )
                """);
        jdbc.execute("""
                CREATE TABLE instance_settings (
                  id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
                  tag_creation_policy TEXT NOT NULL DEFAULT 'any_editor',
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'a@ex.com', 'Admin')", ADMIN);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'b@ex.com', 'Approver')", APPROVER);
        jdbc.update("INSERT INTO global_roles (id, name) VALUES (?, 'Éditeur de documents')", ROLE);
        jdbc.update("INSERT INTO documents (id, title, space_id, doc_type) VALUES (?, 'Doc', ?, 'procedure')",
                DOC, SPACE);
        jdbc.update("INSERT INTO instance_settings (id) VALUES (true)");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM approval_role_assignments");
        jdbc.update("DELETE FROM document_tags");
        jdbc.update("DELETE FROM tags");
        jdbc.update("UPDATE instance_settings SET tag_creation_policy = 'any_editor'");
        service = new TagAdminService(jdbc, identityFacade, auditService);
        tagService = new TagService(jdbc, userSync(), nullAuth(), auditService, identityFacade);
        roleResolver = new ApprovalRoleResolver(jdbc);
        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        admin.setDisplayName("Admin");
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
    }

    private eu.socle.user.UserSyncService userSync() {
        eu.socle.user.UserSyncService us = org.mockito.Mockito.mock(eu.socle.user.UserSyncService.class);
        UserEntity u = new UserEntity();
        u.setId(ADMIN);
        when(us.syncFromJwt(any())).thenReturn(u);
        return us;
    }

    private eu.socle.authz.AuthorizationService nullAuth() {
        eu.socle.authz.AuthorizationService az =
                org.mockito.Mockito.mock(eu.socle.authz.AuthorizationService.class);
        org.mockito.Mockito.doNothing().when(az)
                .requireDocumentRelation(any(), any(), any());
        return az;
    }

    @Test
    void nonAdmin_forbidden() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> service.list(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rename_duplicate_caseInsensitive_409() {
        UUID a = insertTag("IAM");
        insertTag("RGPD");
        assertThatThrownBy(() -> service.rename(jwt, a, new RenameTagRequest("rgpd")))
                .isInstanceOf(CodedStatusException.class)
                .extracting(ex -> ((CodedStatusException) ex).getCode())
                .isEqualTo(ApiErrors.TAG_NAME_CONFLICT);
    }

    @Test
    void merge_withoutDuplicates() {
        UUID a = insertTag("obsolète");
        UUID b = insertTag("à archiver");
        UUID doc2 = UUID.fromString("11111111-1111-1111-1111-111111111112");
        jdbc.update("INSERT INTO documents (id, title, space_id) VALUES (?, 'D2', ?)", doc2, SPACE);
        jdbc.update("INSERT INTO document_tags VALUES (?, ?), (?, ?), (?, ?)",
                DOC, a, DOC, b, doc2, a);
        service.merge(jwt, a, new MergeTagRequest(b, false));
        assertThat(jdbc.queryForList("SELECT tag_id FROM document_tags WHERE document_id = ?", UUID.class, DOC))
                .containsExactly(b);
        assertThat(jdbc.queryForList("SELECT tag_id FROM document_tags WHERE document_id = ?", UUID.class, doc2))
                .containsExactly(b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tags WHERE id = ?", Integer.class, a)).isZero();
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.TAG_MERGED),
                eq("tag"), eq(b), anyMap(), isNull());
    }

    @Test
    void governedTag_delete_409_withAssignments() {
        UUID tag = insertTag("Critique");
        UUID assignId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_role_assignments
                  (id, role_id, subject_type, subject_id, scope_type, scope_ref)
                VALUES (?, ?, 'user', ?, 'tag', ?)
                """, assignId, ROLE, APPROVER, tag.toString());
        assertThatThrownBy(() -> service.delete(jwt, tag))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.GOVERNED_TAG_IN_USE);
                    assertThat(c.getProperties()).containsKey("assignments");
                    @SuppressWarnings("unchecked")
                    List<?> list = (List<?>) c.getProperties().get("assignments");
                    assertThat(list).hasSize(1);
                });
    }

    @Test
    void merge_transferAssignments_canDecideRecalculated() {
        UUID a = insertTag("SourceGov");
        UUID b = insertTag("TargetGov");
        jdbc.update("INSERT INTO document_tags VALUES (?, ?)", DOC, a);
        UUID assignId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_role_assignments
                  (id, role_id, subject_type, subject_id, scope_type, scope_ref)
                VALUES (?, ?, 'user', ?, 'tag', ?)
                """, assignId, ROLE, APPROVER, a.toString());

        assertThat(roleResolver.canDecide(APPROVER, ROLE, DOC)).isTrue();

        // Sans transfert → 409
        assertThatThrownBy(() -> service.merge(jwt, a, new MergeTagRequest(b, false)))
                .isInstanceOf(CodedStatusException.class)
                .extracting(ex -> ((CodedStatusException) ex).getCode())
                .isEqualTo(ApiErrors.GOVERNED_TAG_IN_USE);

        service.merge(jwt, a, new MergeTagRequest(b, true));
        // Doc maintenant sur B ; attribution transférée → canDecide toujours vrai
        assertThat(jdbc.queryForObject(
                "SELECT scope_ref FROM approval_role_assignments WHERE id = ?", String.class, assignId))
                .isEqualToIgnoringCase(b.toString());
        assertThat(roleResolver.canDecide(APPROVER, ROLE, DOC)).isTrue();
        verify(auditService, atLeastOnce()).record(
                eq(ADMIN), eq(false), eq(AuditActions.APPROVAL_ROLE_SCOPE_TRANSFERRED),
                eq("approval_role_assignment"), eq(assignId), anyMap(), isNull());
    }

    @Test
    void restrictedCreation_nonAdmin_403() {
        service.updateCreationPolicy(jwt, new TagCreationPolicyRequest(TagAdminService.POLICY_ADMINS_ONLY));
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        UserEntity editor = new UserEntity();
        editor.setId(APPROVER);
        eu.socle.user.UserSyncService us = org.mockito.Mockito.mock(eu.socle.user.UserSyncService.class);
        when(us.syncFromJwt(any())).thenReturn(editor);
        eu.socle.authz.AuthorizationService az =
                org.mockito.Mockito.mock(eu.socle.authz.AuthorizationService.class);
        org.mockito.Mockito.doNothing().when(az).requireDocumentRelation(any(), any(), any());
        TagService editorTags = new TagService(jdbc, us, az, auditService, identityFacade);

        assertThatThrownBy(() -> editorTags.attach(jwt, DOC, null, "NouveauTag"))
                .isInstanceOf(CodedStatusException.class)
                .extracting(ex -> ((CodedStatusException) ex).getCode())
                .isEqualTo(ApiErrors.TAG_CREATION_RESTRICTED);

        // Attacher un tag existant reste permis
        UUID existing = insertTag("Existant");
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        var att = editorTags.attach(jwt, DOC, existing, null);
        assertThat(att.tag().id()).isEqualTo(existing);
    }

    @Test
    void create_setsCreatedBy_andAudit() {
        TagAdminView v = service.create(jwt, new CreateTagRequest("Nouveau", "#3730E0"));
        assertThat(v.createdByDisplayName()).isEqualTo("Admin");
        assertThat(v.name()).isEqualTo("Nouveau");
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.TAG_CREATED),
                eq("tag"), eq(v.id()), anyMap(), isNull());
    }

    @Test
    void legacyTag_createdByNull_showsNull() {
        UUID id = jdbc.queryForObject(
                "INSERT INTO tags (name, created_by) VALUES ('Legacy', NULL) RETURNING id", UUID.class);
        TagAdminView v = service.list(jwt).tags().stream()
                .filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        assertThat(v.createdByDisplayName()).isNull();
    }

    private UUID insertTag(String name) {
        return jdbc.queryForObject(
                "INSERT INTO tags (name, created_by) VALUES (?, ?) RETURNING id",
                UUID.class, name, ADMIN);
    }
}
