// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.customfield;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.customfield.CustomFieldAdminDtos.CreateFieldRequest;
import eu.socle.customfield.CustomFieldAdminDtos.FieldAdminView;
import eu.socle.customfield.CustomFieldAdminDtos.UpdateFieldRequest;
import eu.socle.identity.IdentityFacade;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomFieldAdminServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static final ObjectMapper MAPPER = new ObjectMapper();
    static JdbcTemplate jdbc;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;
    @Mock eu.socle.user.UserSyncService userSyncService;
    @Mock eu.socle.authz.AuthorizationService authorizationService;

    CustomFieldAdminService admin;
    DocumentCustomFieldService docFields;
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
                  id UUID PRIMARY KEY, email TEXT, display_name TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE custom_field_definitions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  name TEXT NOT NULL,
                  slug TEXT NOT NULL UNIQUE,
                  help_text TEXT,
                  field_type TEXT NOT NULL,
                  scope TEXT NOT NULL DEFAULT 'all_spaces',
                  is_required BOOLEAN NOT NULL DEFAULT false,
                  options JSONB,
                  status TEXT NOT NULL DEFAULT 'active',
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_custom_field_values (
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  field_id UUID NOT NULL REFERENCES custom_field_definitions(id) ON DELETE CASCADE,
                  value JSONB NOT NULL,
                  PRIMARY KEY (document_id, field_id)
                )
                """);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'a@ex.com', 'Admin')", ADMIN);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'Conformité'), (?, 'Autre')", SPACE, OTHER);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc')", DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_custom_field_values");
        jdbc.update("DELETE FROM custom_field_definitions");
        admin = new CustomFieldAdminService(jdbc, MAPPER, identityFacade, auditService);
        docFields = new DocumentCustomFieldService(
                jdbc, MAPPER, userSyncService, authorizationService, auditService);
        UserEntity adminUser = new UserEntity();
        adminUser.setId(ADMIN);
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(adminUser);
        when(userSyncService.syncFromJwt(any())).thenReturn(adminUser);
        org.mockito.Mockito.doNothing().when(authorizationService)
                .requireDocumentRelation(any(), any(), any());
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
    }

    @Test
    void nonAdmin_403() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> admin.list(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fieldTypeLocked_whenValuesExist() throws Exception {
        FieldAdminView f = admin.create(jwt, new CreateFieldRequest(
                "Réf", null, "texte", "all_spaces", false, null, "active"));
        docFields.setValue(jwt, DOC, f.id(), MAPPER.readTree("\"ISO\""));
        assertThatThrownBy(() -> admin.update(jwt, f.id(), new UpdateFieldRequest(
                "Réf", null, "date", "all_spaces", false, null, "active", false)))
                .isInstanceOf(CodedStatusException.class)
                .extracting(ex -> ((CodedStatusException) ex).getCode())
                .isEqualTo(ApiErrors.FIELD_TYPE_LOCKED);
    }

    @Test
    void removeOptionInUse_409_orArchive() throws Exception {
        var opts = MAPPER.readTree("[{\"value\":\"A\",\"label\":\"A\"},{\"value\":\"B\",\"label\":\"B\"}]");
        FieldAdminView f = admin.create(jwt, new CreateFieldRequest(
                "Liste", null, "liste", "all_spaces", false, opts, "active"));
        docFields.setValue(jwt, DOC, f.id(), MAPPER.readTree("\"A\""));

        var onlyB = MAPPER.readTree("[{\"value\":\"B\",\"label\":\"B\"}]");
        assertThatThrownBy(() -> admin.update(jwt, f.id(), new UpdateFieldRequest(
                "Liste", null, "liste", "all_spaces", false, onlyB, "active", false)))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.FIELD_OPTION_IN_USE);
                    assertThat(c.getProperties().get("documentCount")).isEqualTo(1L);
                });

        FieldAdminView archived = admin.update(jwt, f.id(), new UpdateFieldRequest(
                "Liste", null, "liste", "all_spaces", false, onlyB, "active", true));
        assertThat(archived.options().toString()).contains("\"archived\":true");
        // Valeur existante toujours lisible
        assertThat(docFields.list(jwt, DOC).getFirst().value().asText()).isEqualTo("A");
        // Option A plus proposée
        assertThat(docFields.list(jwt, DOC).getFirst().options().toString()).doesNotContain("\"value\":\"A\"");
    }

    @Test
    void deleteUsed_archives() throws Exception {
        FieldAdminView f = admin.create(jwt, new CreateFieldRequest(
                "X", null, "texte", "all_spaces", false, null, "active"));
        docFields.setValue(jwt, DOC, f.id(), MAPPER.readTree("\"v\""));
        FieldAdminView out = admin.deleteOrArchive(jwt, f.id());
        assertThat(out.status()).isEqualTo("archived");
        assertThat(jdbc.queryForObject(
                "SELECT value #>> '{}' FROM document_custom_field_values WHERE field_id = ?",
                String.class, f.id())).isEqualTo("v");
        // brouillon/archivé invisible sur le document
        assertThat(docFields.list(jwt, DOC)).isEmpty();
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.CUSTOM_FIELD_ARCHIVED),
                eq("custom_field_definition"), eq(f.id()), anyMap(), isNull());
    }

    @Test
    void draft_invisibleOnDocuments() {
        admin.create(jwt, new CreateFieldRequest(
                "Draft", null, "texte", "all_spaces", false, null, "draft"));
        assertThat(docFields.list(jwt, DOC)).isEmpty();
    }

    @Test
    void spaceScope_respected() {
        admin.create(jwt, new CreateFieldRequest(
                "Scoped", null, "texte", SPACE.toString(), false, null, "active"));
        admin.create(jwt, new CreateFieldRequest(
                "Other", null, "texte", OTHER.toString(), false, null, "active"));
        List<DocumentCustomFieldService.CustomFieldView> fields = docFields.list(jwt, DOC);
        assertThat(fields).extracting(DocumentCustomFieldService.CustomFieldView::name)
                .containsExactly("Scoped");
    }

    @Test
    void requiredFieldMissing_onPublish() {
        FieldAdminView f = admin.create(jwt, new CreateFieldRequest(
                "Obligatoire", null, "texte", "all_spaces", true, null, "active"));
        assertThatThrownBy(() -> docFields.requireRequiredFieldsFilled(DOC))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.REQUIRED_FIELD_MISSING);
                    @SuppressWarnings("unchecked")
                    List<?> fields = (List<?>) c.getProperties().get("fields");
                    assertThat(fields).hasSize(1);
                });
        // Rempli → OK
        docFields.setValue(jwt, DOC, f.id(), MAPPER.getNodeFactory().textNode("ok"));
        docFields.requireRequiredFieldsFilled(DOC);
    }

    @Test
    void slugImmutable() {
        FieldAdminView f = admin.create(jwt, new CreateFieldRequest(
                "Référence réglementaire", null, "texte", "all_spaces", false, null, "active"));
        assertThat(f.slug()).isEqualTo("reference_reglementaire");
        FieldAdminView renamed = admin.update(jwt, f.id(), new UpdateFieldRequest(
                "Autre nom", null, null, null, null, null, null, false));
        assertThat(renamed.slug()).isEqualTo("reference_reglementaire");
        assertThat(renamed.name()).isEqualTo("Autre nom");
    }
}
