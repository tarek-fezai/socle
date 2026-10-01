// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.config.SocleProperties;
import eu.socle.trash.TrashService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fixture partagée des tests dossiers : un « mini-schéma » en mémoire (espaces, dossiers,
 * documents) exposé via un {@link JdbcTemplate} mocké, sans base de données.
 */
final class FolderTestSupport {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final UserSyncService userSyncService = mock(UserSyncService.class);
    final AuthorizationService authorizationService = mock(AuthorizationService.class);
    final AuditService auditService = mock(AuditService.class);
    final TrashService trashService = mock(TrashService.class);

    final Map<UUID, String> spaces = new LinkedHashMap<>();
    final Map<UUID, Folder> folders = new LinkedHashMap<>();
    final Map<UUID, Doc> docs = new LinkedHashMap<>();

    record Folder(UUID id, UUID spaceId, UUID parentId, String name, int position) {}

    record Doc(UUID id, UUID spaceId, UUID folderId, String title, String visibility, int position) {}

    FolderTestSupport() {
        spaces.put(SPACE, "Espace");
        spaces.put(OTHER_SPACE, "Autre espace");
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        installJdbcStubs();
    }

    FolderService service(int maxDepth) {
        return new FolderService(
                jdbc, userSyncService, authorizationService, auditService, trashService,
                new SocleProperties(null, null, null, null, new SocleProperties.Folders(maxDepth)));
    }

    FolderTestSupport folder(UUID id, UUID spaceId, UUID parentId, String name) {
        folders.put(id, new Folder(id, spaceId, parentId, name, 0));
        return this;
    }

    FolderTestSupport doc(UUID id, UUID spaceId, UUID folderId, String title) {
        docs.put(id, new Doc(id, spaceId, folderId, title, "space", 0));
        return this;
    }

    static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
    }

    static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        return u;
    }

    /** Toutes les instructions SQL (premier argument String) vues par le JdbcTemplate mocké. */
    static List<String> sqlSeen(JdbcTemplate jdbc) {
        List<String> out = new ArrayList<>();
        org.mockito.Mockito.mockingDetails(jdbc).getInvocations().forEach(inv -> {
            if (inv.getArguments().length > 0 && inv.getArguments()[0] instanceof String s) {
                out.add(s);
            }
        });
        return out;
    }

    // ── stubs JDBC ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void installJdbcStubs() {
        // espace actif
        when(jdbc.query(contains("FROM spaces WHERE id"), any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(2);
                    String name = spaces.get(id);
                    if (name == null) {
                        return List.of();
                    }
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("name")).thenReturn(name);
                    return List.of(mapper.mapRow(rs, 0));
                });

        // dossier actif par id
        when(jdbc.query(contains("FROM folders WHERE id"), any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    Folder f = folders.get((UUID) inv.getArgument(2));
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return f == null ? List.of() : List.of(mapper.mapRow(folderRs(f), 0));
                });

        // document actif par id
        when(jdbc.query(contains("FROM documents WHERE id"), any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    Doc d = docs.get((UUID) inv.getArgument(2));
                    RowMapper<Object> mapper = inv.getArgument(1);
                    return d == null ? List.of() : List.of(mapper.mapRow(docRs(d), 0));
                });

        // parent_folder_id (folderDepth) — ResultSetExtractor
        when(jdbc.query(contains("SELECT parent_folder_id FROM folders"),
                any(ResultSetExtractor.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    Folder f = folders.get((UUID) inv.getArgument(2));
                    ResultSetExtractor<Object> extractor = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(f != null);
                    when(rs.getObject(1)).thenReturn(f == null ? null : f.parentId());
                    return extractor.extractData(rs);
                });

        // INSERT INTO folders (id, space_id, parent_folder_id, name, position, created_by)
        when(jdbc.update(contains("INSERT INTO folders"), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(1);
                    folders.put(id, new Folder(id, inv.getArgument(2), inv.getArgument(3),
                            inv.getArgument(4), inv.getArgument(5)));
                    return 1;
                });

        // profondeur max des descendants
        when(jdbc.queryForObject(contains("SELECT COALESCE(MAX(d), 0)"), eq(Integer.class), any(UUID.class)))
                .thenAnswer(inv -> maxDescendantDepth(inv.getArgument(2)));

        // isDescendant(ancestor, candidate)
        when(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> isInSubtree(inv.getArgument(2), inv.getArgument(3)));

        // sous-dossiers directs
        when(jdbc.query(contains("WHERE parent_folder_id = ? AND deleted_at IS NULL"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID parent = inv.getArgument(2);
                    RowMapper<Object> mapper = inv.getArgument(1);
                    List<Object> ids = new ArrayList<>();
                    for (Folder f : folders.values()) {
                        if (parent.equals(f.parentId())) {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getObject("id")).thenReturn(f.id());
                            ids.add(mapper.mapRow(rs, 0));
                        }
                    }
                    return ids;
                });

        // documents directs d'un dossier
        when(jdbc.query(contains("WHERE folder_id = ? AND deleted_at IS NULL"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID folder = inv.getArgument(2);
                    RowMapper<Object> mapper = inv.getArgument(1);
                    List<Object> ids = new ArrayList<>();
                    for (Doc d : docs.values()) {
                        if (folder.equals(d.folderId())) {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getObject("id")).thenReturn(d.id());
                            ids.add(mapper.mapRow(rs, 0));
                        }
                    }
                    return ids;
                });

        // tree() : tous les dossiers de l'espace
        when(jdbc.query(contains("ORDER BY position ASC, lower(name) ASC"),
                any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID space = inv.getArgument(2);
                    RowMapper<Object> mapper = inv.getArgument(1);
                    List<Object> rows = new ArrayList<>();
                    for (Folder f : folders.values()) {
                        if (space.equals(f.spaceId())) {
                            rows.add(mapper.mapRow(folderRs(f), 0));
                        }
                    }
                    return rows;
                });

        // tree() : documents (args = spaceId + ids viewer)
        when(jdbc.query(contains("lower(title)"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    // varargs dépliés par Mockito : [sql, mapper, spaceId, id1, id2, ...]
                    Object[] args = java.util.Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length);
                    UUID space = (UUID) args[0];
                    List<UUID> ids = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) {
                        ids.add((UUID) args[i]);
                    }
                    List<Object> rows = new ArrayList<>();
                    for (Doc d : docs.values()) {
                        if (space.equals(d.spaceId()) && ids.contains(d.id())) {
                            rows.add(mapper.mapRow(docRs(d), 0));
                        }
                    }
                    return rows;
                });
    }

    private int maxDescendantDepth(UUID folderId) {
        int max = 0;
        for (Folder f : folders.values()) {
            if (f.id().equals(folderId)) {
                continue;
            }
            int d = 0;
            UUID cur = f.parentId();
            while (cur != null) {
                d++;
                if (cur.equals(folderId)) {
                    max = Math.max(max, d);
                    break;
                }
                Folder p = folders.get(cur);
                cur = p == null ? null : p.parentId();
            }
        }
        return max;
    }

    /** {@code candidate} est-il {@code ancestor} lui-même ou l'un de ses descendants ? */
    private boolean isInSubtree(UUID ancestor, UUID candidate) {
        UUID cur = candidate;
        int guard = 0;
        while (cur != null && guard++ < 100) {
            if (cur.equals(ancestor)) {
                return true;
            }
            Folder f = folders.get(cur);
            cur = f == null ? null : f.parentId();
        }
        return false;
    }

    private static ResultSet folderRs(Folder f) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(f.id());
        when(rs.getObject("space_id")).thenReturn(f.spaceId());
        when(rs.getObject("parent_folder_id")).thenReturn(f.parentId());
        when(rs.getString("name")).thenReturn(f.name());
        when(rs.getInt("position")).thenReturn(f.position());
        when(rs.getObject("created_by")).thenReturn(USER);
        return rs;
    }

    private static ResultSet docRs(Doc d) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(d.id());
        when(rs.getObject("space_id")).thenReturn(d.spaceId());
        when(rs.getObject("folder_id")).thenReturn(d.folderId());
        when(rs.getString("title")).thenReturn(d.title());
        when(rs.getString("status")).thenReturn("brouillon");
        when(rs.getString("visibility")).thenReturn(d.visibility());
        when(rs.getInt("position")).thenReturn(d.position());
        return rs;
    }
}
