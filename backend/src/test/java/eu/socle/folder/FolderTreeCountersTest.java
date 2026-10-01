// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.config.SocleProperties;
import eu.socle.trash.TrashService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
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
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compteurs post-filtrage : un restricted invisible ne gonfle pas documentCount.
 * Volume : vérifs bornées à DocumentScope.space.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderTreeCountersTest {

    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID FOLDER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID D1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID D2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID D3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock TrashService trashService;
    @Mock Jwt jwt;

    FolderService service;

    @BeforeEach
    void setUp() {
        service = new FolderService(
                jdbc, userSyncService, authorizationService, auditService, trashService,
                new SocleProperties(null, null, null, null, new SocleProperties.Folders(5)));
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(jwt)).thenReturn(user);
    }

    @Test
    void tree_documentCountExcludesRestrictedInvisible() throws Exception {
        when(jdbc.query(contains("FROM spaces WHERE id"), any(RowMapper.class), eq(SPACE)))
                .thenReturn(List.of("Engineering"));
        when(jdbc.query(contains("FROM folders"), any(RowMapper.class), eq(SPACE)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(FOLDER);
                    when(rs.getObject("space_id")).thenReturn(SPACE);
                    when(rs.getObject("parent_folder_id")).thenReturn(null);
                    when(rs.getString("name")).thenReturn("Procs");
                    when(rs.getInt("position")).thenReturn(0);
                    when(rs.getObject("created_by")).thenReturn(USER);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(authorizationService.filterByFolderViewer(eq(USER), any()))
                .thenReturn(List.of(FOLDER));
        // 3 docs in space, only 2 viewable (restricted filtered out)
        when(authorizationService.listViewableDocumentIds(eq(USER), any()))
                .thenReturn(List.of(D1, D2));
        when(jdbc.query(contains("FROM documents"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(
                            mapDoc(mapper, D1, FOLDER, "A"),
                            mapDoc(mapper, D2, FOLDER, "B"));
                });

        var tree = service.tree(jwt, SPACE, null);

        assertThat(tree.folders()).hasSize(1);
        assertThat(tree.folders().getFirst().documentCount()).isEqualTo(2);
        assertThat(tree.folders().getFirst().documents()).hasSize(2);

        ArgumentCaptor<DocumentScope> scopeCaptor = ArgumentCaptor.forClass(DocumentScope.class);
        verify(authorizationService).listViewableDocumentIds(eq(USER), scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().spaceId()).isEqualTo(SPACE);
        assertThat(scopeCaptor.getValue().folderId()).isNull();
    }

    private static Object mapDoc(RowMapper<Object> mapper, UUID id, UUID folderId, String title)
            throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("space_id")).thenReturn(SPACE);
        when(rs.getObject("folder_id")).thenReturn(folderId);
        when(rs.getString("title")).thenReturn(title);
        when(rs.getString("status")).thenReturn("valide");
        when(rs.getString("visibility")).thenReturn("space");
        when(rs.getInt("position")).thenReturn(0);
        return mapper.mapRow(rs, 0);
    }
}
