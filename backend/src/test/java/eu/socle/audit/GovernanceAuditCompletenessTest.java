// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.TransclusionResolver;
import eu.socle.export.ExportService;
import eu.socle.integrations.SiemConnectorService;
import eu.socle.integrations.SiemHttpClient;
import eu.socle.integrations.WebhookEndpointService;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.space.SpaceDtos.CreateSpaceRequest;
import eu.socle.space.SpaceService;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.team.GroupDtos.CreateGroupRequest;
import eu.socle.team.GroupService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import eu.socle.workflowdef.WorkflowDefinitionDtos.StepInput;
import eu.socle.workflowdef.WorkflowDefinitionDtos.UpsertRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Complétude : Spaces, Groupes, Workflows, Intégrations, Export produisent chacun
 * une entrée d'audit (metadata-only).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceAuditCompletenessTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ROLE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock JdbcTemplate jdbc;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock DocumentRepository documentRepository;
    @Mock AuditService auditService;
    @Mock ExternalReferencePolicy externalReferencePolicy;
    @Mock ExternalReferenceNotify externalReferenceNotify;

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        u.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        when(authorizationService.hasRelation(any(), any(), any(), any())).thenReturn(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.update(eq("INSERT INTO spaces (id, name, color, external_reference, created_at) VALUES (?, ?, ?, 'open', now())"),
                any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO space_owners"), any(), any())).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO groups"), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO approval_workflows"), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO approval_workflow_steps"), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO siem_connectors"), any(), any(), any())).thenReturn(1);
    }

    @Test
    void spaceCreate_recordsSpaceCreated() {
        when(jdbc.query(contains("FROM spaces"), any(ResultSetExtractor.class), any()))
                .thenAnswer(inv -> {
                    ResultSetExtractor<?> ex = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getObject("id")).thenReturn(SPACE);
                    when(rs.getString("name")).thenReturn("Espace A");
                    when(rs.getString("color")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getString("external_reference")).thenReturn("open");
                    return ex.extractData(rs);
                });
        when(jdbc.queryForObject(contains("FROM space_owners"), eq(Integer.class), any(), any()))
                .thenReturn(1);
        when(jdbc.query(contains("is_responsible"), any(ResultSetExtractor.class), any(), any()))
                .thenReturn(true);

        new SpaceService(jdbc, userSyncService, authorizationService, auditService)
                .create(jwt(), new CreateSpaceRequest("Espace A", null));

        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.SPACE_CREATED),
                eq("space"), any(UUID.class), anyMap(), isNull());
    }

    @Test
    void groupCreate_recordsGroupCreated() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("name", "Equipe");
        row.put("created_by", USER);
        row.put("created_at", Timestamp.from(Instant.now()));
        when(jdbc.queryForMap(contains("FROM groups"), any())).thenReturn(row);
        when(jdbc.query(contains("group_members"), any(RowMapper.class), any())).thenReturn(List.of());

        new GroupService(jdbc, userSyncService, authorizationService, auditService, mock(eu.socle.identity.IdentityFacade.class))
                .create(jwt(), new CreateGroupRequest("Equipe"));

        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.GROUP_CREATED),
                eq("group"), any(UUID.class), anyMap(), isNull());
    }

    @Test
    void workflowCreate_recordsWorkflowCreated() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.queryForObject(contains("global_roles"), eq(Integer.class), any())).thenReturn(1);
        when(jdbc.queryForObject(contains("approval_requests"), eq(Integer.class), any())).thenReturn(0);
        when(jdbc.query(contains("FROM approval_workflows"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getString("name")).thenReturn("WF");
                    when(rs.getObject("scope_space_id")).thenReturn(null);
                    when(rs.getString("scope_doc_type")).thenReturn(null);
                    when(rs.getString("status")).thenReturn("active");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(rm.mapRow(rs, 0));
                });
        when(jdbc.query(contains("approval_workflow_steps"), any(RowMapper.class), any()))
                .thenReturn(List.of());

        new ApprovalWorkflowDefinitionService(jdbc, auditService, mock(eu.socle.audit.AuditActorResolver.class))
                .create(new UpsertRequest(
                        "WF", null, null, "active",
                        List.of(new StepInput(1, 24, ROLE, null))));

        verify(auditService).record(
                isNull(), eq(false), eq(AuditActions.WORKFLOW_CREATED),
                eq("workflow"), any(UUID.class), anyMap(), isNull());
    }

    @Test
    void siemAndWebhookCreate_recordIntegrationAudit() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        when(jdbc.query(contains("FROM siem_connectors WHERE id"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
                    when(rs.getString("provider")).thenReturn("splunk");
                    when(rs.getString("config")).thenReturn(
                            "{\"endpoint\":\"https://hec.example\",\"format\":\"hec\",\"hec_token\":\"tok\"}");
                    when(rs.getString("status")).thenReturn("disconnected");
                    when(rs.getTimestamp("connected_at")).thenReturn(null);
                    return List.of(rm.mapRow(rs, 0));
                });

        new SiemConnectorService(
                jdbc, mapper, new SiemPayloadFormatter(mapper),
                (url, body, headers) -> new SiemHttpClient.Result(200, "ok"),
                auditService,
                mock(eu.socle.audit.AuditActorResolver.class))
                .create("splunk", Map.of("endpoint", "https://hec.example", "format", "hec", "hec_token", "tok"));

        verify(auditService).record(
                isNull(), eq(false), eq(AuditActions.SIEM_CONNECTOR_CREATED),
                eq("siem_connector"), any(UUID.class), anyMap(), isNull());

        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            PreparedStatementCreator psc = inv.getArgument(0);
            Connection con = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            when(con.prepareStatement(any())).thenReturn(ps);
            when(con.createArrayOf(eq("text"), any())).thenReturn(mock(Array.class));
            psc.createPreparedStatement(con);
            return 1;
        });
        when(jdbc.query(contains("FROM webhook_endpoints WHERE id"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    Array arr = mock(Array.class);
                    when(arr.getArray()).thenReturn(new String[]{"document.published"});
                    when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getString("url")).thenReturn("https://hooks.example");
                    when(rs.getArray("subscribed_events")).thenReturn(arr);
                    when(rs.getString("status")).thenReturn("active");
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(rm.mapRow(rs, 0));
                });

        new WebhookEndpointService(jdbc, auditService, mock(eu.socle.audit.AuditActorResolver.class))
                .create("https://hooks.example", List.of("document.published"), "active");

        verify(auditService).record(
                isNull(), eq(false), eq(AuditActions.WEBHOOK_ENDPOINT_CREATED),
                eq("webhook_endpoint"), any(UUID.class), anyMap(), isNull());
    }

    @Test
    void exportDocument_recordsExportWithoutBodyLeak() {
        doNothing().when(authorizationService).requireDocumentRelation(USER, DOC, "viewer");
        when(externalReferencePolicy.allowsInterWorkspaceEdge(any(), any())).thenReturn(true);

        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC);
        doc.setSpaceId(SPACE);
        doc.setTitle("Rapport");
        doc.setBody(Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", "SECRET_BODY"))))));
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(doc));

        var store = new RelationalDocumentStore(mock(DocumentVersionRepository.class));
        ExportService export = new ExportService(
                documentRepository, store,
                new TransclusionResolver(
                        documentRepository, store, authorizationService,
                        externalReferencePolicy, externalReferenceNotify),
                authorizationService, userSyncService, auditService, jdbc);

        export.exportDocument(jwt(), DOC);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_EXPORTED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("title", "Rapport");
        assertThat(meta.getValue()).doesNotContainKeys("body", "content", "resolvedBody");
        assertThat(meta.getValue().toString()).doesNotContain("SECRET_BODY");
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .claim("realm_access", Map.of("roles", List.of("contributeur")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
