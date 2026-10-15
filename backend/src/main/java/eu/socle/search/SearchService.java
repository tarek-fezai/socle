// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.search;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.AuthorizationService.ReadableScope;
import eu.socle.search.SearchDtos.SearchHit;
import eu.socle.search.SearchDtos.SearchResponse;
import eu.socle.user.UserSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recherche full-text : présélection SQL (peut sur-inclure) puis Check OpenFGA viewer,
 * puis {@code ts_headline} uniquement sur les ids autorisés. Voir {@code docs/search.md}.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_AUTHZ_REFILL_ITERATIONS = 3;

    /**
     * Présélection SQL — peut inclure trop (colonne visibility / direct_access orphelin),
     * jamais trop peu. Exactitude via {@link AuthorizationService#filterByDocumentViewer}.
     */
    public static final String READABLE_PREDICATE = AuthorizationService.READABLE_PREDICATE;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;

    public SearchService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(
            Jwt jwt,
            String q,
            UUID spaceId,
            String tag,
            String docType,
            Integer limit
    ) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return new SearchResponse("", List.of(), 0, true, null);
        }

        var user = userSyncService.syncFromJwt(jwt);
        ReadableScope scope = authorizationService.readableScope(user.getId());

        int lim = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String tagFilter = blankToNull(tag);
        String typeFilter = blankToNull(docType);

        int estimatedTotal = countPreselected(scope, query, spaceId, tagFilter, typeFilter);

        List<Candidate> accepted = new ArrayList<>();
        int offset = 0;
        int iterations = 0;
        boolean moreCandidatesPossible = true;
        while (accepted.size() < lim && iterations < MAX_AUTHZ_REFILL_ITERATIONS) {
            iterations++;
            List<Candidate> batch = preselectPage(
                    scope, query, spaceId, tagFilter, typeFilter, lim, offset);
            if (batch.isEmpty()) {
                moreCandidatesPossible = false;
                break;
            }
            List<UUID> allowed = authorizationService.filterByDocumentViewer(
                    user.getId(),
                    batch.stream().map(Candidate::id).toList());
            Set<UUID> allowedSet = new LinkedHashSet<>(allowed);
            for (Candidate c : batch) {
                if (allowedSet.contains(c.id()) && accepted.size() < lim) {
                    accepted.add(c);
                }
            }
            offset += batch.size();
            if (batch.size() < lim) {
                moreCandidatesPossible = false;
                break;
            }
        }

        String warning = null;
        if (accepted.size() < lim
                && moreCandidatesPossible
                && iterations >= MAX_AUTHZ_REFILL_ITERATIONS) {
            warning = "search_authz_refill_truncated";
            log.warn(
                    "Search page incomplete after {} authz refill iterations (got {}/{})",
                    iterations, accepted.size(), lim);
        }

        List<SearchHit> hits = accepted.isEmpty()
                ? List.of()
                : loadHeadlines(accepted, query, user.getId());

        return new SearchResponse(query, hits, estimatedTotal, true, warning);
    }

    private int countPreselected(
            ReadableScope scope,
            String query,
            UUID spaceId,
            String tagFilter,
            String typeFilter
    ) {
        String sql = """
                SELECT count(*)::int
                  FROM documents d
                  JOIN spaces s ON s.id = d.space_id AND s.deleted_at IS NULL
                 WHERE d.deleted_at IS NULL
                   AND %s
                   AND d.search_vector @@ plainto_tsquery('french', ?)
                   AND (?::uuid IS NULL OR d.space_id = ?::uuid)
                   AND (?::text IS NULL OR d.doc_type = ?)
                   AND (?::text IS NULL OR EXISTS (
                         SELECT 1 FROM document_tags dt
                         JOIN tags t ON t.id = dt.tag_id
                        WHERE dt.document_id = d.id
                          AND lower(t.name) = lower(?)
                       ))
                """.formatted(READABLE_PREDICATE);
        return jdbc.query(sql, ps -> bindScopeAndFilters(ps, 1, scope, query, spaceId, tagFilter, typeFilter),
                (rs, i) -> rs.getInt(1)).stream().findFirst().orElse(0);
    }

    private List<Candidate> preselectPage(
            ReadableScope scope,
            String query,
            UUID spaceId,
            String tagFilter,
            String typeFilter,
            int lim,
            int offset
    ) {
        String sql = """
                SELECT d.id, d.title, d.space_id, s.name AS space_name, d.doc_type, d.status,
                       d.updated_at,
                       ts_rank(d.search_vector, plainto_tsquery('french', ?)) AS rank,
                       fp.id AS page_folder_id, fp.name AS page_folder_name
                  FROM documents d
                  JOIN spaces s ON s.id = d.space_id AND s.deleted_at IS NULL
                  LEFT JOIN folders fp ON fp.page_document_id = d.id
                 WHERE d.deleted_at IS NULL
                   AND %s
                   AND d.search_vector @@ plainto_tsquery('french', ?)
                   AND (?::uuid IS NULL OR d.space_id = ?::uuid)
                   AND (?::text IS NULL OR d.doc_type = ?)
                   AND (?::text IS NULL OR EXISTS (
                         SELECT 1 FROM document_tags dt
                         JOIN tags t ON t.id = dt.tag_id
                        WHERE dt.document_id = d.id
                          AND lower(t.name) = lower(?)
                       ))
                 ORDER BY rank DESC, d.updated_at DESC
                 LIMIT ? OFFSET ?
                """.formatted(READABLE_PREDICATE);

        return jdbc.query(sql, ps -> {
            int idx = 1;
            ps.setString(idx++, query); // ts_rank
            idx = bindScopeAndFilters(ps, idx, scope, query, spaceId, tagFilter, typeFilter);
            ps.setInt(idx++, lim);
            ps.setInt(idx, offset);
        }, (rs, i) -> new Candidate(
                (UUID) rs.getObject("id"),
                rs.getString("title"),
                (UUID) rs.getObject("space_id"),
                rs.getString("space_name"),
                rs.getString("doc_type"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getDouble("rank"),
                (UUID) rs.getObject("page_folder_id"),
                rs.getString("page_folder_name")
        ));
    }

    /** Bind: scope arrays + fts query + space/type/tag filters. Returns next index. */
    private static int bindScopeAndFilters(
            PreparedStatement ps,
            int startIdx,
            ReadableScope scope,
            String query,
            UUID spaceId,
            String tagFilter,
            String typeFilter
    ) throws SQLException {
        int idx = startIdx;
        Array sView = ps.getConnection().createArrayOf("uuid", scope.spaceViewerIds().toArray());
        Array sOwner = ps.getConnection().createArrayOf("uuid", scope.spaceOwnerIds().toArray());
        Array dDirect = ps.getConnection().createArrayOf("uuid", scope.directDocumentIds().toArray());
        Array fView = ps.getConnection().createArrayOf("uuid", scope.folderViewerIds().toArray());
        ps.setArray(idx++, sView);
        ps.setArray(idx++, sOwner);
        ps.setArray(idx++, dDirect);
        ps.setArray(idx++, fView);
        ps.setString(idx++, query);
        if (spaceId == null) {
            ps.setObject(idx++, null);
            ps.setObject(idx++, null);
        } else {
            ps.setObject(idx++, spaceId);
            ps.setObject(idx++, spaceId);
        }
        ps.setString(idx++, typeFilter);
        ps.setString(idx++, typeFilter);
        ps.setString(idx++, tagFilter);
        ps.setString(idx++, tagFilter);
        return idx;
    }

    private List<SearchHit> loadHeadlines(List<Candidate> accepted, String query, UUID userId) {
        List<UUID> ids = accepted.stream().map(Candidate::id).toList();
        Map<UUID, String> excerpts = new HashMap<>();
        jdbc.query("""
                SELECT d.id,
                       ts_headline(
                           'french',
                           coalesce(d.title, '') || E'\\n' || coalesce(d.body::text, ''),
                           plainto_tsquery('french', ?),
                           'MaxFragments=1, MaxWords=32, MinWords=12'
                       ) AS excerpt
                  FROM documents d
                 WHERE d.id = ANY (?)
                """, ps -> {
            ps.setString(1, query);
            Array arr = ps.getConnection().createArrayOf("uuid", ids.toArray());
            ps.setArray(2, arr);
        }, (rs, i) -> {
            excerpts.put((UUID) rs.getObject("id"), sanitizeExcerpt(rs.getString("excerpt")));
            return null;
        });

        Map<UUID, List<eu.socle.document.DocumentDtos.TagRef>> tags = loadTags(ids);
        Set<UUID> favoriteIds = loadFavoriteDocumentIds(userId, ids);

        List<SearchHit> hits = new ArrayList<>(accepted.size());
        for (Candidate c : accepted) {
            boolean page = c.pageFolderId() != null;
            String title = page ? "Dossier — " + c.pageFolderName() : c.title();
            hits.add(new SearchHit(
                    c.id(),
                    title,
                    excerpts.getOrDefault(c.id(), ""),
                    c.spaceId(),
                    c.spaceName(),
                    c.docType(),
                    c.status(),
                    c.updatedAt(),
                    c.rank(),
                    tags.getOrDefault(c.id(), List.of()),
                    favoriteIds.contains(c.id()),
                    page ? c.pageFolderId() : null));
        }
        return hits;
    }

    private Map<UUID, List<eu.socle.document.DocumentDtos.TagRef>> loadTags(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<eu.socle.document.DocumentDtos.TagRef>> out = new HashMap<>();
        jdbc.query("""
                SELECT dt.document_id, t.id, t.name, t.color
                  FROM document_tags dt
                  JOIN tags t ON t.id = dt.tag_id
                 WHERE dt.document_id = ANY (?)
                 ORDER BY t.name ASC
                """, ps -> {
            Array arr = ps.getConnection().createArrayOf("uuid", documentIds.toArray());
            ps.setArray(1, arr);
        }, (rs, i) -> {
            UUID docId = (UUID) rs.getObject("document_id");
            out.computeIfAbsent(docId, k -> new ArrayList<>()).add(
                    new eu.socle.document.DocumentDtos.TagRef(
                            (UUID) rs.getObject("id"),
                            rs.getString("name"),
                            rs.getString("color"),
                            false));
            return null;
        });
        return out;
    }

    private Set<UUID> loadFavoriteDocumentIds(UUID userId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> out = new HashSet<>();
        jdbc.query("""
                SELECT target_id FROM favorites
                 WHERE user_id = ? AND target_type = 'document' AND target_id = ANY (?)
                """, ps -> {
            ps.setObject(1, userId);
            Array arr = ps.getConnection().createArrayOf("uuid", documentIds.toArray());
            ps.setArray(2, arr);
        }, (rs, i) -> {
            out.add((UUID) rs.getObject("target_id"));
            return null;
        });
        return out;
    }

    /** Rebuild explicite (tests / backfill) — les triggers couvrent le chemin nominal. */
    public void reindexDocument(UUID documentId) {
        jdbc.update("SELECT documents_rebuild_search_vector(?)", documentId);
    }

    private static String sanitizeExcerpt(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("[{}\\[\\]\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record Candidate(
            UUID id,
            String title,
            UUID spaceId,
            String spaceName,
            String docType,
            String status,
            Instant updatedAt,
            double rank,
            UUID pageFolderId,
            String pageFolderName
    ) {}
}
