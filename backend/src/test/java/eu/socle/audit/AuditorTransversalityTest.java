// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transversalité + metadata-only du journal (couche requête / sanitization).
 * Voir {@code docs/audit-logging.md}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditorTransversalityTest {

    static final UUID FOREIGN_SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock JdbcTemplate jdbcTemplate;
    AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    /** Test central : aucun filtre appartenance / OpenFGA / spaces dans la requête. */
    @Test
    void list_sqlHasNoSpaceMembershipOrOpenFgaJoin() {
        service.list("space", FOREIGN_SPACE, null, "space.*", null, null, 0, 50);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        verify(jdbcTemplate).query(pageSql.capture(), any(RowMapper.class), any(Object[].class));

        for (String sql : List.of(countSql.getValue(), pageSql.getValue())) {
            assertThat(sql).doesNotContainIgnoringCase("space_owners");
            assertThat(sql).doesNotContainIgnoringCase("space_members");
            assertThat(sql).doesNotContainIgnoringCase("external_reference");
            assertThat(sql).doesNotContainIgnoringCase("openfga");
            assertThat(sql).doesNotContainIgnoringCase("group_members");
            assertThat(sql).contains("FROM audit_log_events");
        }
        assertThat(pageSql.getValue()).contains("LEFT JOIN users");
    }

    @Test
    void list_byForeignSpaceResourceId_doesNotRequireCallerMembership() {
        service.list(null, FOREIGN_SPACE, null, null, null, null, 0, 50);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), args.capture());
        assertThat(args.getValue()).containsExactly(FOREIGN_SPACE);
    }

    @Test
    void sanitizeMetadata_stripsDocumentBodyKeys_keepsTitle() {
        Map<String, Object> raw = Map.of(
                "title", "Confidentiel",
                "body", Map.of("type", "doc", "content", List.of(Map.of("text", "SECRET"))),
                "bodySnapshot", "{\"type\":\"doc\"}",
                "content", "SECRET_PLAIN",
                "resolvedBody", Map.of("x", 1),
                "previousBody", Map.of("y", 2),
                "spaceId", FOREIGN_SPACE.toString()
        );
        Map<String, Object> safe = AuditService.sanitizeMetadata(raw);
        assertThat(safe).containsEntry("title", "Confidentiel");
        assertThat(safe).containsEntry("spaceId", FOREIGN_SPACE.toString());
        assertThat(safe).doesNotContainKeys(
                "body", "bodySnapshot", "content", "resolvedBody", "previousBody");
        assertThat(safe.values().stream().map(Object::toString).reduce("", String::concat))
                .doesNotContain("SECRET");
    }

    /**
     * Style « projection empoisonnée » (cf. {@code GitReadCanonicalTest}) :
     * on plante délibérément un corps confidentiel dans les métadonnées brutes
     * passées à {@code recordSync}, puis on vérifie que le JSON persisté
     * (argument INSERT) ne le contient plus — pas seulement que le helper
     * unitaire filtre en isolation.
     */
    @Test
    void recordSync_poisonedBodyInMetadata_isAbsentFromPersistedJson() {
        when(jdbcTemplate.queryForObject(
                contains("RETURNING id"), eq(Long.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(42L);

        AuditService writer = new AuditService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        Map<String, Object> poison = new java.util.LinkedHashMap<>();
        poison.put("title", "Doc restreint");
        poison.put("body", Map.of(
                "type", "doc",
                "content", List.of(Map.of("type", "text", "text", "CORPS_CONFIDENTIEL_EMPOISONNE"))));
        poison.put("content", "CORPS_CONFIDENTIEL_EMPOISONNE");
        poison.put("bodySnapshot", "{\"text\":\"CORPS_CONFIDENTIEL_EMPOISONNE\"}");
        poison.put("resolvedBody", Map.of("leak", "CORPS_CONFIDENTIEL_EMPOISONNE"));
        poison.put("previousBody", Map.of("leak", "CORPS_CONFIDENTIEL_EMPOISONNE"));

        writer.recordSync(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                false,
                AuditActions.DOCUMENT_UPDATED,
                "document",
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                poison,
                null);

        ArgumentCaptor<Object[]> insertArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(
                contains("RETURNING id"), eq(Long.class), insertArgs.capture());
        Object[] args = insertArgs.getValue();
        // doInsert(ip null) : actor, system, action, type, id, metadataJson, createdAt
        String persistedJson = (String) args[5];
        assertThat(persistedJson).contains("Doc restreint");
        assertThat(persistedJson).doesNotContain("CORPS_CONFIDENTIEL_EMPOISONNE");
        assertThat(persistedJson).doesNotContain("\"body\"");
        assertThat(persistedJson).doesNotContain("bodySnapshot");
        assertThat(persistedJson).doesNotContain("resolvedBody");
        assertThat(persistedJson).doesNotContain("previousBody");
        // clé "content" retirée (pas le mot dans un titre légitime)
        assertThat(persistedJson).doesNotContain("\"content\"");
    }
}
