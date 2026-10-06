// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentVisibility;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class VisibilityListObjectsCeilingTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final int CEILING = 1000;

    @Mock OpenFgaClient openFgaClient;
    @Mock JdbcTemplate jdbc;

    AuthorizationService service;

    @BeforeEach
    void setUp() {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga("http://localhost", "s", "m", CEILING, false, null, null, null),
                null,
                null,
                null);
        service = new AuthorizationService(openFgaClient, jdbc, props);
    }

    @Test
    void listObjectsAtCeiling_logsWarning(CapturedOutput output) throws Exception {
        List<String> objects = new ArrayList<>();
        for (int i = 0; i < CEILING; i++) {
            objects.add("document:" + UUID.randomUUID());
        }
        ClientListObjectsResponse response = mock(ClientListObjectsResponse.class);
        when(response.getObjects()).thenReturn(objects);
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(response));

        assertThat(service.listObjectsDocumentIds(USER)).hasSize(CEILING);
        assertThat(output.getOut() + output.getErr()).containsIgnoringCase("plafond");
    }

    @Test
    void listViewable_usesSqlFilterWithSpaceAndDirectScopes() throws Exception {
        UUID spaceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID folderId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID docId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        ClientListObjectsResponse spaces = mock(ClientListObjectsResponse.class);
        when(spaces.getObjects()).thenReturn(List.of("space:" + spaceId));
        ClientListObjectsResponse owners = mock(ClientListObjectsResponse.class);
        when(owners.getObjects()).thenReturn(List.of());
        ClientListObjectsResponse direct = mock(ClientListObjectsResponse.class);
        when(direct.getObjects()).thenReturn(List.of("document:" + docId));
        ClientListObjectsResponse folders = mock(ClientListObjectsResponse.class);
        when(folders.getObjects()).thenReturn(List.of("folder:" + folderId));

        when(openFgaClient.listObjects(any())).thenAnswer(inv -> {
            var req = inv.getArgument(0, dev.openfga.sdk.api.client.model.ClientListObjectsRequest.class);
            if ("space".equals(req.getType()) && "viewer".equals(req.getRelation())) {
                return CompletableFuture.completedFuture(spaces);
            }
            if ("space".equals(req.getType()) && "owner".equals(req.getRelation())) {
                return CompletableFuture.completedFuture(owners);
            }
            if ("folder".equals(req.getType())) {
                return CompletableFuture.completedFuture(folders);
            }
            return CompletableFuture.completedFuture(direct);
        });

        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(docId));

        var batchItem = new dev.openfga.sdk.api.client.model.ClientBatchCheckItem()
                .correlationId(docId.toString());
        when(openFgaClient.batchCheck(any())).thenReturn(CompletableFuture.completedFuture(
                new dev.openfga.sdk.api.client.model.ClientBatchCheckResponse(List.of(
                        new dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse(
                                true, batchItem, docId.toString(), null)))));

        assertThat(service.listViewableDocumentIds(USER, DocumentScope.global())).containsExactly(docId);
    }

    @Test
    void migration_ownerToEditor_onlyCreator_addsInheritFrom() throws Exception {
        AuthorizationService authz = mock(AuthorizationService.class);
        String creator = "user:" + USER;
        String space = "space:00000000-0000-0000-0000-000000000001";

        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(0);
        when(jdbc.query(contains("SELECT id, visibility, created_by"), any(RowMapper.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(DOC);
            when(rs.getString("visibility")).thenReturn(DocumentVisibility.SPACE);
            when(rs.getObject("created_by")).thenReturn(USER);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(authz.readAllTuples("document", DOC)).thenReturn(List.of(
                new AuthorizationService.AccessTuple(creator, "owner", "document:" + DOC),
                new AuthorizationService.AccessTuple(space, "parent", "document:" + DOC)
        ));

        var report = new VisibilityTupleMigrationService(authz, jdbc, new ObjectMapper()).migrate();

        assertThat(report.documentsScanned()).isEqualTo(1);
        assertThat(report.ownersConvertedToEditor()).isEqualTo(1);
        assertThat(report.inheritFromAdded()).isEqualTo(1);
        assertThat(report.errors()).isZero();
        verify(authz).convertDirectOwnerToEditor(DOC, creator);
        verify(authz).ensureInheritFrom(DOC, space);
        verify(authz, org.mockito.Mockito.never()).grantOrganisationViewer(any());
    }
}
