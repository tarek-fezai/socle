// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.customfield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.customfield.DocumentCustomFieldService.CustomFieldView;
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

/** Champs personnalisés sur PostgreSQL réel (schéma V1 minimal) ; authz / identité / audit mockés. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentCustomFieldServiceTest {

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
    static final UUID OTHER_SPACE = UUID.fromString("33333333-3333-3333-3333-333333333334");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_EMPTY_SPACE = UUID.fromString("11111111-1111-1111-1111-111111111112");
    static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;

    DocumentCustomFieldService service;
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
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
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
                  field_type TEXT NOT NULL CHECK (field_type IN
                    ('texte','liste','multi_selection','date','nombre','lien','personne','case_a_cocher')),
                  scope TEXT NOT NULL DEFAULT 'all_spaces',
                  is_required BOOLEAN NOT NULL DEFAULT false,
                  options JSONB,
                  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','draft','archived')),
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
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S'), (?, 'T')", SPACE, OTHER_SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc')", DOC, SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc T')",
                DOC_EMPTY_SPACE, OTHER_SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_custom_field_values");
        jdbc.update("DELETE FROM custom_field_definitions");
        service = new DocumentCustomFieldService(
                jdbc, MAPPER, userSyncService, authorizationService, auditService);
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
    }

    private UUID field(String slug, String type, String scope, boolean required, String options, String status) {
        return jdbc.queryForObject("""
                INSERT INTO custom_field_definitions (name, slug, field_type, scope, is_required, options, status)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id
                """, UUID.class, "Champ " + slug, slug, type, scope, required, options, status);
    }

    private UUID textField(String slug) {
        return field(slug, "texte", "all_spaces", false, null, "active");
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(expected);
    }

    // ---------- GET ----------

    @Test
    void get_withNoDefinitions_returnsEmptyList() {
        assertThat(service.list(jwt, DOC)).isEmpty();
        verify(authorizationService).requireDocumentRelation(USER, DOC, "viewer");
    }

    @Test
    void get_returnsOnlyApplicableActiveDefinitions_withValues() {
        UUID global = textField("ref");
        field("archived", "texte", "all_spaces", false, null, "archived");
        field("draft", "texte", "all_spaces", false, null, "draft");
        UUID scoped = field("scoped", "texte", SPACE.toString(), false, null, "active");
        field("other", "texte", OTHER_SPACE.toString(), false, null, "active");
        jdbc.update("INSERT INTO document_custom_field_values VALUES (?, ?, '\"ISO 27001\"'::jsonb)", DOC, global);

        List<CustomFieldView> fields = service.list(jwt, DOC);

        assertThat(fields).extracting(CustomFieldView::id).containsExactlyInAnyOrder(global, scoped);
        CustomFieldView ref = fields.stream().filter(f -> f.id().equals(global)).findFirst().orElseThrow();
        assertThat(ref.value().asText()).isEqualTo("ISO 27001");
        assertThat(ref.fieldType()).isEqualTo("texte");
        CustomFieldView sc = fields.stream().filter(f -> f.id().equals(scoped)).findFirst().orElseThrow();
        assertThat(sc.value()).isNull();
    }

    @Test
    void get_withoutViewerRight_is403() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (viewer)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "viewer");
        assertThatThrownBy(() -> service.list(jwt, DOC)).satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
    }

    @Test
    void get_unknownDocument_is404() {
        assertThatThrownBy(() -> service.list(jwt, UNKNOWN)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    // ---------- PUT ----------

    @Test
    void put_textValue_isStoredTrimmed_updated_andAudited() {
        UUID f = textField("ref");

        CustomFieldView v = service.setValue(jwt, DOC, f, json("\"  RGPD art. 32  \""));
        assertThat(v.value().asText()).isEqualTo("RGPD art. 32");
        service.setValue(jwt, DOC, f, json("\"v2\""));

        assertThat(jdbc.queryForObject(
                "SELECT value #>> '{}' FROM document_custom_field_values WHERE document_id = ? AND field_id = ?",
                String.class, DOC, f)).isEqualTo("v2");
        assertThat(service.list(jwt, DOC).getFirst().value().asText()).isEqualTo("v2");
        verify(authorizationService, org.mockito.Mockito.atLeastOnce())
                .requireDocumentRelation(USER, DOC, "editor");
        verify(auditService, org.mockito.Mockito.times(2)).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_CUSTOM_FIELD_UPDATED), eq("document"), eq(DOC),
                anyMap(), any());
    }

    @Test
    void put_blankOrNull_clearsValue() {
        UUID f = textField("ref");
        service.setValue(jwt, DOC, f, json("\"x\""));

        assertThat(service.setValue(jwt, DOC, f, json("null")).value()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_custom_field_values", Integer.class)).isZero();
    }

    @Test
    void put_clearingRequiredField_is400() {
        UUID f = field("req", "texte", "all_spaces", true, null, "active");
        assertThatThrownBy(() -> service.setValue(jwt, DOC, f, json("\"  \"")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void put_withoutEditRight_is403_andWritesNothing() {
        UUID f = textField("ref");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        assertThatThrownBy(() -> service.setValue(jwt, DOC, f, json("\"x\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_custom_field_values", Integer.class)).isZero();
        verify(auditService, never()).record(any(), eq(false), any(), any(), any(), any(), any());
    }

    @Test
    void put_unknownDocument_or_unknownOrNonApplicableField_is404() {
        UUID f = textField("ref");
        UUID otherSpaceField = field("other", "texte", OTHER_SPACE.toString(), false, null, "active");
        UUID archived = field("arch", "texte", "all_spaces", false, null, "archived");

        assertThatThrownBy(() -> service.setValue(jwt, UNKNOWN, f, json("\"x\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, UUID.randomUUID(), json("\"x\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, otherSpaceField, json("\"x\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, archived, json("\"x\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void put_validatesValueByType() {
        UUID text = textField("t");
        UUID num = field("n", "nombre", "all_spaces", false, null, "active");
        UUID date = field("d", "date", "all_spaces", false, null, "active");
        UUID list = field("l", "liste", "all_spaces", false, "[\"A\",\"B\"]", "active");
        UUID multi = field("m", "multi_selection", "all_spaces", false, "[\"A\",\"B\"]", "active");

        assertThatThrownBy(() -> service.setValue(jwt, DOC, text, json("42")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, text,
                json("\"" + "x".repeat(DocumentCustomFieldService.MAX_TEXT_LENGTH + 1) + "\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, num, json("\"12\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, date, json("\"31/12/2026\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, list, json("\"C\"")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.setValue(jwt, DOC, multi, json("[\"A\",\"Z\"]")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));

        assertThat(service.setValue(jwt, DOC, num, json("12.5")).value().asDouble()).isEqualTo(12.5);
        assertThat(service.setValue(jwt, DOC, date, json("\"2026-12-31\"")).value().asText())
                .isEqualTo("2026-12-31");
        assertThat(service.setValue(jwt, DOC, list, json("\"B\"")).value().asText()).isEqualTo("B");
        assertThat(service.setValue(jwt, DOC, multi, json("[\"A\",\"A\",\"B\"]")).value().size()).isEqualTo(2);
    }
}
