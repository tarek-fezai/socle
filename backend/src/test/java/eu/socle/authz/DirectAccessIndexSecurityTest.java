package eu.socle.authz;

import eu.socle.config.SocleProperties;
import eu.socle.authz.DocumentScope;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckResponse;
import dev.openfga.sdk.api.client.model.ClientListObjectsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * direct_access est un index ListObjects — n'accorde aucun droit viewer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectAccessIndexSecurityTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock OpenFgaClient openFgaClient;
    @Mock JdbcTemplate jdbc;

    AuthorizationService authz;
    VisibilityDriftService drift;

    @BeforeEach
    void setUp() {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga("http://localhost", "s", "m", 1000, false, null, null, null),
                null,
                null,
                null);
        authz = new AuthorizationService(openFgaClient, jdbc, props);
        drift = new VisibilityDriftService(authz, jdbc);
    }

    @Test
    void orphanDirectAccess_checkViewerDenied_requireDocumentRelation403() throws Exception {
        ClientCheckResponse denied = mock(ClientCheckResponse.class);
        when(denied.getAllowed()).thenReturn(false);
        when(openFgaClient.check(any(ClientCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(denied));

        assertThat(authz.hasRelation(USER, "document", DOC, "viewer")).isFalse();
        assertThatThrownBy(() -> authz.requireDocumentRelation(USER, DOC, "viewer"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filterByDocumentViewer_excludesDeniedIds() throws Exception {
        UUID allowed = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID denied = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        ClientBatchCheckItem itemAllowed = new ClientBatchCheckItem()
                .correlationId(allowed.toString());
        ClientBatchCheckItem itemDenied = new ClientBatchCheckItem()
                .correlationId(denied.toString());
        ClientBatchCheckResponse batch = new ClientBatchCheckResponse(List.of(
                new ClientBatchCheckSingleResponse(true, itemAllowed, allowed.toString(), null),
                new ClientBatchCheckSingleResponse(false, itemDenied, denied.toString(), null)
        ));
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(batch));

        assertThat(authz.filterByDocumentViewer(USER, List.of(allowed, denied)))
                .containsExactly(allowed);
    }

    @Test
    void listViewable_appliesFinalCheck_afterSqlPreselection() throws Exception {
        stubEmptyListObjects();
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(DOC));

        ClientBatchCheckItem item = new ClientBatchCheckItem().correlationId(DOC.toString());
        when(openFgaClient.batchCheck(any(ClientBatchCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new ClientBatchCheckResponse(List.of(
                        new ClientBatchCheckSingleResponse(false, item, DOC.toString(), null)
                ))));

        assertThat(authz.listViewableDocumentIds(USER, DocumentScope.global())).isEmpty();
    }

    @Test
    void directAccessDrift_detectsOrphanWithoutDirectGrant() throws Exception {
        when(jdbc.query(contains("SELECT id, visibility"), any(RowMapper.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(DOC);
            when(rs.getString("visibility")).thenReturn("space");
            when(rs.getObject("space_id")).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            when(rs.getObject("folder_id")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });

        String subject = "user:" + USER;
        stubRead(List.of(
                new AuthorizationService.AccessTuple(subject, "direct_access", "document:" + DOC),
                new AuthorizationService.AccessTuple(
                        "space:00000000-0000-0000-0000-000000000001", "parent", "document:" + DOC)
        ));

        var report = drift.listDirectAccessDrift();
        assertThat(report.orphanCount()).isEqualTo(1);
        assertThat(report.orphans().getFirst().user()).isEqualTo(subject);
    }

    @Test
    void directAccessDrift_ignoresWhenEditorPresent() throws Exception {
        when(jdbc.query(contains("SELECT id, visibility"), any(RowMapper.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(DOC);
            when(rs.getString("visibility")).thenReturn("space");
            when(rs.getObject("space_id")).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            when(rs.getObject("folder_id")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });

        String subject = "user:" + USER;
        stubRead(List.of(
                new AuthorizationService.AccessTuple(subject, "editor", "document:" + DOC),
                new AuthorizationService.AccessTuple(subject, "direct_access", "document:" + DOC)
        ));

        assertThat(drift.listDirectAccessDrift().orphanCount()).isZero();
    }

    private void stubEmptyListObjects() throws Exception {
        ClientListObjectsResponse empty = mock(ClientListObjectsResponse.class);
        when(empty.getObjects()).thenReturn(List.of());
        when(openFgaClient.listObjects(any())).thenReturn(CompletableFuture.completedFuture(empty));
    }

    private void stubRead(List<AuthorizationService.AccessTuple> tuples) throws Exception {
        List<dev.openfga.sdk.api.model.Tuple> apiTuples = tuples.stream().map(t -> {
            var key = new dev.openfga.sdk.api.model.TupleKey()
                    .user(t.user())
                    .relation(t.relation())
                    ._object(t.object());
            return new dev.openfga.sdk.api.model.Tuple().key(key);
        }).toList();
        var response = mock(dev.openfga.sdk.api.client.model.ClientReadResponse.class);
        when(response.getTuples()).thenReturn(apiTuples);
        when(openFgaClient.read(any())).thenReturn(CompletableFuture.completedFuture(response));
    }
}
