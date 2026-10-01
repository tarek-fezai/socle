// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.trash;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrashServiceTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab");
    static final UUID FOLDER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID FOLDER2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbc");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;

    TrashService service;
    Jwt jwt;

    @BeforeEach
    void setUp() {
        service = new TrashService(
                jdbc, userSyncService, authorizationService, auditService,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        doNothing().when(authorizationService).requireDocumentRelation(any(), any(), any());
        doNothing().when(authorizationService).requireFolderRelation(any(), any(), any());
        doNothing().when(authorizationService).requireSpaceRelation(any(), any(), any());
    }

    @Test
    void softDeleteDocument_setsTombstoneAndTrashItem() throws Exception {
        stubDocumentLookup(DOC, "Doc", SPACE, null, false);
        when(jdbc.update(contains("UPDATE documents"), any(), any(), eq(DOC))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO trash_items"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var result = service.softDeleteDocument(jwt, DOC);

        assertThat(result.documents()).isEqualTo(1);
        verify(jdbc).update(contains("INSERT INTO trash_items"), any(), eq("document"), eq(DOC),
                any(), eq(USER), any(), any());
        verify(auditService).record(eq(USER), eq(false), eq(AuditActions.DOCUMENT_TRASHED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void softDeleteDocument_forbiddenWithoutOpenFga() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC, "editor");

        assertThatThrownBy(() -> service.softDeleteDocument(jwt, DOC))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(jdbc, never()).update(contains("INSERT INTO trash_items"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void softDeleteSpace_marksEveryResourceAndOneTrashRowEach() throws Exception {
        stubSpaceLookup(SPACE, false);
        when(jdbc.query(contains("FROM documents"), any(RowMapper.class), eq(SPACE)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(
                            mapDoc(mapper, DOC, "D1", SPACE, FOLDER),
                            mapDoc(mapper, DOC2, "D2", SPACE, FOLDER2));
                });
        when(jdbc.query(contains("ORDER BY parent_folder_id"), any(RowMapper.class), eq(SPACE)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(
                            mapFolder(mapper, FOLDER, SPACE, null, false),
                            mapFolder(mapper, FOLDER2, SPACE, null, false));
                });
        when(jdbc.update(contains("UPDATE documents"), any(), any(), any(UUID.class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE folders"), any(), any(), any(UUID.class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE spaces"), any(), any(), eq(SPACE))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO trash_items"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var result = service.softDeleteSpace(jwt, SPACE);

        assertThat(result.documents()).isEqualTo(2);
        assertThat(result.folders()).isEqualTo(2);
        assertThat(result.spaces()).isEqualTo(1);
        // 2 docs + 2 folders + 1 space
        verify(jdbc, times(5)).update(contains("INSERT INTO trash_items"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void softDeleteFolder_cascades_oneTrashItemPerResource() throws Exception {
        when(jdbc.query(contains("WITH RECURSIVE tree"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("folder_id = ? AND deleted_at IS NULL"), any(RowMapper.class), eq(FOLDER)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapDoc(mapper, DOC, "RootDoc"));
                });
        when(jdbc.query(contains("FROM folders WHERE id"), any(RowMapper.class), eq(FOLDER)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapFolder(mapper, FOLDER, SPACE, null, false));
                });
        when(jdbc.update(contains("UPDATE documents"), any(), any(), any(UUID.class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE folders"), any(), any(), any(UUID.class))).thenReturn(1);
        when(jdbc.update(contains("INSERT INTO trash_items"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var result = service.softDeleteFolder(jwt, FOLDER);

        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.folders()).isEqualTo(1);
        verify(jdbc, times(2)).update(contains("INSERT INTO trash_items"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restore_documentClearsTombstone() throws Exception {
        UUID trashId = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items WHERE id"), any(RowMapper.class), eq(trashId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapTrash(mapper, trashId, "document", DOC));
                });
        when(authorizationService.hasRelation(USER, "document", DOC, "editor")).thenReturn(true);
        when(jdbc.query(contains("SELECT folder_id FROM documents"), any(RowMapper.class), eq(DOC)))
                .thenReturn(List.of());
        when(jdbc.update(contains("UPDATE documents SET deleted_at = NULL"), eq(DOC))).thenReturn(1);
        when(jdbc.update(contains("DELETE FROM trash_items"), eq("document"), eq(DOC))).thenReturn(1);

        var result = service.restore(jwt, trashId);

        assertThat(result.documents()).isEqualTo(1);
        verify(auditService).record(eq(USER), eq(false), eq(AuditActions.DOCUMENT_RESTORED_FROM_TRASH),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void restore_documentBlockedWhenParentFolderStillTrashed() throws Exception {
        UUID trashId = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items WHERE id"), any(RowMapper.class), eq(trashId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapTrash(mapper, trashId, "document", DOC));
                });
        when(authorizationService.hasRelation(USER, "document", DOC, "editor")).thenReturn(true);
        when(jdbc.query(contains("SELECT folder_id FROM documents"), any(RowMapper.class), eq(DOC)))
                .thenReturn(List.of(FOLDER));
        when(jdbc.queryForObject(contains("FROM folders WHERE id"), eq(Boolean.class), eq(FOLDER)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.restore(jwt, trashId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(409);
                    assertThat(rse.getReason()).containsIgnoringCase("dossier parent");
                });
        verify(jdbc, never()).update(contains("SET deleted_at = NULL"), any(UUID.class));
    }

    @Test
    void restore_folderRestoresDescendantDocuments() throws Exception {
        UUID trashId = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items WHERE id"), any(RowMapper.class), eq(trashId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapTrash(mapper, trashId, "folder", FOLDER));
                });
        when(authorizationService.hasRelation(USER, "folder", FOLDER, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(USER, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(USER, "document", DOC2, "editor")).thenReturn(true);
        when(jdbc.query(contains("SELECT parent_folder_id FROM folders"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("WITH RECURSIVE tree"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("folder_id = ? AND deleted_at IS NOT NULL"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of(DOC, DOC2));
        when(jdbc.update(contains("UPDATE documents SET deleted_at = NULL"), any(UUID.class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE folders SET deleted_at = NULL"), eq(FOLDER))).thenReturn(1);
        when(jdbc.update(contains("DELETE FROM trash_items"), any(), any(UUID.class))).thenReturn(1);

        var result = service.restore(jwt, trashId);

        assertThat(result.documents()).isEqualTo(2);
        assertThat(result.folders()).isEqualTo(1);
        verify(jdbc, times(2)).update(contains("UPDATE documents SET deleted_at = NULL"), any(UUID.class));
        verify(auditService).record(eq(USER), eq(false), eq(AuditActions.FOLDER_RESTORED_FROM_TRASH),
                eq("folder"), eq(FOLDER), anyMap(), isNull());
    }

    @Test
    void restore_forbiddenWithoutOpenFga() throws Exception {
        UUID trashId = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items WHERE id"), any(RowMapper.class), eq(trashId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapTrash(mapper, trashId, "document", DOC));
                });
        when(authorizationService.hasRelation(USER, "document", DOC, "editor")).thenReturn(false);
        when(authorizationService.hasRelation(USER, "document", DOC, "owner")).thenReturn(false);

        assertThatThrownBy(() -> service.restore(jwt, trashId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void purgeExpired_hardDeletesAfterRetention() throws Exception {
        UUID trashId = UUID.randomUUID();
        Instant past = NOW.minus(TrashService.RETENTION).minusSeconds(60);
        when(jdbc.query(contains("purge_at <="), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(trashId);
                    when(rs.getString("resource_type")).thenReturn("document");
                    when(rs.getObject("resource_id")).thenReturn(DOC);
                    when(rs.getString("snapshot")).thenReturn("{}");
                    when(rs.getObject("deleted_by")).thenReturn(USER);
                    when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(past));
                    when(rs.getTimestamp("purge_at")).thenReturn(Timestamp.from(past.plus(TrashService.RETENTION)));
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.update(contains("DELETE FROM documents"), eq(DOC))).thenReturn(1);
        when(jdbc.update(contains("DELETE FROM trash_items WHERE id"), eq(trashId))).thenReturn(1);
        when(jdbc.update(contains("DELETE FROM trash_items t"))).thenReturn(0);

        int n = service.purgeExpired();

        assertThat(n).isEqualTo(1);
        verify(jdbc).update(contains("DELETE FROM documents"), eq(DOC));
        verify(auditService).record(isNull(), eq(true), eq(AuditActions.DOCUMENT_PURGED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void softDeleteDocument_alreadyDeleted_conflict() throws Exception {
        stubDocumentLookup(DOC, "Doc", SPACE, null, true);

        assertThatThrownBy(() -> service.softDeleteDocument(jwt, DOC))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(409));
    }

    @Test
    void softDeleteFolder_forbiddenOnDescendantDocument_beforeAnyWrite() throws Exception {
        when(jdbc.query(contains("WITH RECURSIVE tree"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("FROM folders WHERE id"), any(RowMapper.class), eq(FOLDER)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapFolder(mapper, FOLDER, SPACE, null, false));
                });
        when(jdbc.query(contains("folder_id = ? AND deleted_at IS NULL"), any(RowMapper.class), eq(FOLDER)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapDoc(mapper, DOC, "RootDoc"), mapDoc(mapper, DOC2, "Other"));
                });
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(USER, DOC2, "editor");

        assertThatThrownBy(() -> service.softDeleteFolder(jwt, FOLDER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(jdbc, never()).update(contains("UPDATE documents"), any(), any(), any());
        verify(jdbc, never()).update(contains("UPDATE folders"), any(), any(), any());
        verify(jdbc, never()).update(contains("INSERT INTO trash_items"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restore_folderForbiddenOnDescendant_beforeAnyWrite() throws Exception {
        UUID trashId = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items WHERE id"), any(RowMapper.class), eq(trashId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapTrash(mapper, trashId, "folder", FOLDER));
                });
        when(authorizationService.hasRelation(USER, "folder", FOLDER, "editor")).thenReturn(true);
        when(jdbc.query(contains("SELECT parent_folder_id FROM folders"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("WITH RECURSIVE tree"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of());
        when(jdbc.query(contains("folder_id = ? AND deleted_at IS NOT NULL"), any(RowMapper.class), eq(FOLDER)))
                .thenReturn(List.of(DOC, DOC2));
        when(authorizationService.hasRelation(USER, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(USER, "document", DOC2, "editor")).thenReturn(false);
        when(authorizationService.hasRelation(USER, "document", DOC2, "owner")).thenReturn(false);

        assertThatThrownBy(() -> service.restore(jwt, trashId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
        verify(jdbc, never()).update(contains("SET deleted_at = NULL"), any(UUID.class));
    }

    @Test
    void list_filtersByViewer_twoAccessProfiles() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(jdbc.query(contains("FROM trash_items t"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(
                            mapTrash(mapper, t1, "document", DOC, "Alpha"),
                            mapTrash(mapper, t2, "document", DOC2, "Beta"));
                });
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(USER, "document", DOC2, "viewer")).thenReturn(false);
        when(authorizationService.hasRelation(USER, "document", DOC2, "editor")).thenReturn(false);
        when(authorizationService.hasRelation(USER, "document", DOC2, "owner")).thenReturn(false);

        var page = service.list(jwt, null, 0, 50);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().resourceId()).isEqualTo(DOC);
        assertThat(page.items().getFirst().title()).isEqualTo("Alpha");
        assertThat(page.items().getFirst().purgeAt()).isEqualTo(NOW.plus(TrashService.RETENTION));
    }

    @Test
    void list_paginationAndTypeFilterAndSort() throws Exception {
        UUID tOld = UUID.randomUUID();
        UUID tMid = UUID.randomUUID();
        UUID tNew = UUID.randomUUID();
        Instant older = NOW.minusSeconds(100);
        Instant mid = NOW.minusSeconds(50);
        when(jdbc.query(contains("WHERE t.resource_type = ?"), any(RowMapper.class), eq("document")))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    // SQL already ORDER BY deleted_at DESC — newest first
                    return List.of(
                            mapTrashAt(mapper, tNew, "document", DOC, "New", NOW),
                            mapTrashAt(mapper, tMid, "document", DOC2, "Mid", mid),
                            mapTrashAt(mapper, tOld, "folder", FOLDER, "OldFolder", older));
                });
        when(authorizationService.hasRelation(eq(USER), any(), any(), eq("viewer"))).thenReturn(true);

        var page = service.list(jwt, "document", 1, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().title()).isEqualTo("Mid");
        assertThat(page.items().getFirst().purgeAt()).isEqualTo(mid.plus(TrashService.RETENTION));
    }

    @SuppressWarnings("unchecked")
    private void stubDocumentLookup(UUID id, String title, UUID spaceId, UUID folderId, boolean deleted)
            throws Exception {
        when(jdbc.query(contains("FROM documents WHERE id"), any(RowMapper.class), eq(id)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return List.of(mapDoc(mapper, id, title, spaceId, folderId));
                });
        when(jdbc.queryForObject(contains("deleted_at IS NOT NULL"), eq(Boolean.class), eq(id)))
                .thenReturn(deleted);
    }

    @SuppressWarnings("unchecked")
    private void stubSpaceLookup(UUID id, boolean deleted) throws Exception {
        when(jdbc.query(contains("FROM spaces WHERE id"), any(RowMapper.class), eq(id)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(id);
                    when(rs.getString("name")).thenReturn("Espace");
                    when(rs.getString("color")).thenReturn("#3730E0");
                    when(rs.getTimestamp("deleted_at")).thenReturn(deleted ? Timestamp.from(NOW) : null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private static Object mapDoc(RowMapper<Object> mapper, UUID id, String title) throws Exception {
        return mapDoc(mapper, id, title, SPACE, FOLDER);
    }

    private static Object mapDoc(RowMapper<Object> mapper, UUID id, String title, UUID spaceId, UUID folderId)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("space_id")).thenReturn(spaceId);
        when(rs.getObject("folder_id")).thenReturn(folderId);
        when(rs.getString("title")).thenReturn(title);
        when(rs.getString("status")).thenReturn("brouillon");
        return mapper.mapRow(rs, 0);
    }

    private static Object mapFolder(RowMapper<Object> mapper, UUID id, UUID spaceId, UUID parent, boolean deleted)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("space_id")).thenReturn(spaceId);
        when(rs.getObject("parent_folder_id")).thenReturn(parent);
        when(rs.getString("title")).thenReturn("Folder");
        when(rs.getTimestamp("deleted_at")).thenReturn(deleted ? Timestamp.from(NOW) : null);
        return mapper.mapRow(rs, 0);
    }

    private static Object mapTrash(RowMapper<Object> mapper, UUID id, String type, UUID resourceId)
            throws Exception {
        return mapTrash(mapper, id, type, resourceId, "x");
    }

    private static Object mapTrash(RowMapper<Object> mapper, UUID id, String type, UUID resourceId, String title)
            throws Exception {
        return mapTrashAt(mapper, id, type, resourceId, title, NOW);
    }

    private static Object mapTrashAt(
            RowMapper<Object> mapper, UUID id, String type, UUID resourceId, String title, Instant deletedAt
    ) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getString("resource_type")).thenReturn(type);
        when(rs.getObject("resource_id")).thenReturn(resourceId);
        String field = "space".equals(type) ? "name" : "title";
        when(rs.getString("snapshot")).thenReturn("{\"" + field + "\":\"" + title + "\"}");
        when(rs.getObject("deleted_by")).thenReturn(USER);
        when(rs.getString("deleted_by_name")).thenReturn("Awa Auditeur");
        when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(deletedAt));
        when(rs.getTimestamp("purge_at")).thenReturn(Timestamp.from(deletedAt.plus(TrashService.RETENTION)));
        return mapper.mapRow(rs, 0);
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        return u;
    }
}
