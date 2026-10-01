// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentVisibility;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisibilityAuthzCorrectifsTest {

    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DELEGATED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final String SPACE = "space:00000000-0000-0000-0000-000000000001";

    @Mock OpenFgaClient openFgaClient;
    @Mock JdbcTemplate jdbc;

    AuthorizationService authz;
    VisibilityTupleMigrationService migration;
    VisibilityDriftService drift;

    @BeforeEach
    void setUp() throws Exception {
        SocleProperties props = new SocleProperties(
                null,
                new SocleProperties.OpenFga("http://localhost", "s", "m", 1000, false, null, null, null),
                null,
                null,
                null);
        authz = new AuthorizationService(openFgaClient, jdbc, props);
        migration = new VisibilityTupleMigrationService(authz, jdbc, new ObjectMapper());
        drift = new VisibilityDriftService(authz, jdbc);
        when(openFgaClient.write(any(ClientWriteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        mock(dev.openfga.sdk.api.client.model.ClientWriteResponse.class)));
        stubRead(List.of());
    }

    @Test
    void migration_convertsOnlyCreatorOwner_preservesDelegatedOwner() throws Exception {
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(0);
        when(jdbc.query(contains("SELECT id, visibility, created_by"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(DOC);
                    when(rs.getString("visibility")).thenReturn(DocumentVisibility.SPACE);
                    when(rs.getObject("created_by")).thenReturn(CREATOR);
                    return List.of(mapper.mapRow(rs, 0));
                });
        stubRead(List.of(
                tuple("user:" + CREATOR, "owner", "document:" + DOC),
                tuple("user:" + DELEGATED, "owner", "document:" + DOC),
                tuple(SPACE, "parent", "document:" + DOC)
        ));

        var report = migration.migrate();

        assertThat(report.skipped()).isFalse();
        assertThat(report.ownersConvertedToEditor()).isEqualTo(1);
        assertThat(report.delegatedOwnersPreserved()).isEqualTo(1);
        verify(jdbc).update(
                contains("authz_migrations"),
                eq(VisibilityTupleMigrationService.MIGRATION_NAME),
                anyString());

        org.mockito.Mockito.clearInvocations(openFgaClient);
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(1);
        assertThat(migration.migrate().skipped()).isTrue();
        verify(openFgaClient, never()).write(any(ClientWriteRequest.class));
    }

    @Test
    void migration_force_doesNotConvertDelegatedOwnerAgain() throws Exception {
        when(jdbc.queryForObject(contains("authz_migrations"), eq(Integer.class), any()))
                .thenReturn(1);
        when(jdbc.query(contains("SELECT id, visibility, created_by"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(DOC);
                    when(rs.getString("visibility")).thenReturn(DocumentVisibility.SPACE);
                    when(rs.getObject("created_by")).thenReturn(CREATOR);
                    return List.of(mapper.mapRow(rs, 0));
                });
        stubRead(List.of(
                tuple("user:" + CREATOR, "editor", "document:" + DOC),
                tuple("user:" + CREATOR, "direct_access", "document:" + DOC),
                tuple("user:" + DELEGATED, "owner", "document:" + DOC),
                tuple("user:" + DELEGATED, "direct_access", "document:" + DOC),
                tuple(SPACE, "parent", "document:" + DOC),
                tuple(SPACE, "inherit_from", "document:" + DOC)
        ));

        var report = migration.migrateForce();
        assertThat(report.ownersConvertedToEditor()).isZero();
        assertThat(report.delegatedOwnersPreserved()).isEqualTo(1);
    }

    @Test
    void applyVisibilityTuples_atomicWrite_failureLeavesNeitherTuple() throws Exception {
        when(openFgaClient.write(any(ClientWriteRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("OpenFGA down")));

        assertThatThrownBy(() ->
                authz.applyVisibilityTuples(
                        DOC, SPACE, DocumentVisibility.RESTRICTED, DocumentVisibility.ORGANISATION))
                .isInstanceOf(ResponseStatusException.class);

        ArgumentCaptor<ClientWriteRequest> cap = ArgumentCaptor.forClass(ClientWriteRequest.class);
        verify(openFgaClient, atLeastOnce()).write(cap.capture());
        assertThat(cap.getValue().getWrites()).extracting(ClientTupleKey::getRelation)
                .containsExactlyInAnyOrder("inherit_from", "viewer");
        verify(openFgaClient, never()).writeTuples(any());
        verify(openFgaClient, never()).deleteTuples(any());
    }

    @Test
    void provisionDocumentAccess_singleWrite() throws Exception {
        authz.provisionDocumentAccess(
                DOC,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                CREATOR,
                DocumentVisibility.ORGANISATION);

        ArgumentCaptor<ClientWriteRequest> cap = ArgumentCaptor.forClass(ClientWriteRequest.class);
        verify(openFgaClient).write(cap.capture());
        assertThat(cap.getValue().getWrites()).extracting(ClientTupleKey::getRelation)
                .contains("parent", "inherit_from", "viewer", "editor", "direct_access");
    }

    @Test
    void visibilityDrift_detectsMissingUserStar() throws Exception {
        when(jdbc.query(contains("SELECT id, visibility"), any(RowMapper.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(DOC);
            when(rs.getString("visibility")).thenReturn(DocumentVisibility.ORGANISATION);
            when(rs.getObject("space_id"))
                    .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            when(rs.getObject("folder_id")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });
        stubRead(List.of(
                tuple(SPACE, "parent", "document:" + DOC),
                tuple(SPACE, "inherit_from", "document:" + DOC)
        ));

        var report = drift.listDrift();
        assertThat(report.driftCount()).isEqualTo(1);
        assertThat(report.items().get(0).issues()).contains("missing_user_star_viewer");
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

    private static AuthorizationService.AccessTuple tuple(String user, String relation, String object) {
        return new AuthorizationService.AccessTuple(user, relation, object);
    }
}
