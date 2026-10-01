// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import eu.socle.config.SocleProperties;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentScopePerformanceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID FOLDER = UUID.fromString("f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f0");

    @Mock OpenFgaClient openFgaClient;
    @Mock JdbcTemplate jdbc;

    AuthorizationService authz;
    AtomicInteger checksCounted;

    @BeforeEach
    void setUp() throws Exception {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga("http://localhost", "s", "m", 1000, false, 50, 4, 500),
                null,
                null);
        authz = new AuthorizationService(openFgaClient, jdbc, props);
        checksCounted = new AtomicInteger();

        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(empty));

        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class))).thenAnswer(inv -> {
            ClientBatchCheckRequest req = inv.getArgument(0);
            List<ClientBatchCheckSingleResponse> results = new ArrayList<>();
            for (ClientBatchCheckItem item : req.getChecks()) {
                checksCounted.addAndGet(1);
                results.add(new ClientBatchCheckSingleResponse(
                        true, item, item.getCorrelationId(), null));
            }
            return CompletableFuture.completedFuture(new ClientBatchCheckResponse(results));
        });
    }

    @Test
    void spaceScope_checksOnlySpaceCandidates_notWholeInstance() {
        List<UUID> spaceDocs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            spaceDocs.add(UUID.nameUUIDFromBytes(("space-doc-" + i).getBytes()));
        }
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(spaceDocs);

        List<UUID> result = authz.listViewableDocumentIds(USER, DocumentScope.space(SPACE));

        assertThat(result).hasSize(50);
        assertThat(checksCounted.get()).isEqualTo(50);
        assertThat(checksCounted.get()).isLessThan(3000);
    }

    @Test
    void folderScope_checksOnlyFolderCandidates() {
        List<UUID> folderDocs = List.of(
                UUID.nameUUIDFromBytes("f1".getBytes()),
                UUID.nameUUIDFromBytes("f2".getBytes()),
                UUID.nameUUIDFromBytes("f3".getBytes()));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("WITH RECURSIVE")) {
                        return List.of(FOLDER);
                    }
                    return folderDocs;
                });

        List<UUID> result = authz.listViewableDocumentIds(USER, DocumentScope.folder(FOLDER));

        assertThat(result).hasSize(3);
        assertThat(checksCounted.get()).isEqualTo(3);
    }

    @Test
    void documentListPage_checksApproxPageSize() {
        List<UUID> page = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            page.add(UUID.nameUUIDFromBytes(("page-" + i).getBytes()));
        }
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(page);

        List<UUID> preselected = authz.preselectDocumentIdsPage(USER, DocumentScope.global(), 20, 0);
        List<UUID> allowed = authz.filterByDocumentViewer(USER, preselected, "documents-list");

        assertThat(preselected).hasSize(20);
        assertThat(allowed).hasSize(20);
        assertThat(checksCounted.get()).isEqualTo(20);
    }

    @Test
    void equivalence_scopedSpace_matchesFilterOfGlobalIntersection() {
        UUID inSpace = UUID.nameUUIDFromBytes("in-space".getBytes());
        UUID outside = UUID.nameUUIDFromBytes("outside".getBytes());

        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(inSpace))
                .thenReturn(List.of(inSpace, outside));

        List<UUID> scoped = authz.listViewableDocumentIds(USER, DocumentScope.space(SPACE));
        List<UUID> global = authz.listViewableDocumentIds(USER, DocumentScope.global());
        List<UUID> globalInSpace = global.stream().filter(inSpace::equals).toList();

        assertThat(scoped).containsExactly(inSpace);
        assertThat(globalInSpace).containsExactlyElementsOf(scoped);
    }

    @Test
    void batchCheck_splitsIntoConfiguredChunks() throws Exception {
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(UUID.randomUUID());
        }
        authz.filterByDocumentViewer(USER, many, "chunk-test");

        ArgumentCaptor<ClientBatchCheckRequest> captor =
                ArgumentCaptor.forClass(ClientBatchCheckRequest.class);
        verify(openFgaClient, times(3)).batchCheck(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> r.getChecks().size() <= 50);
        assertThat(checksCounted.get()).isEqualTo(120);
    }
}
