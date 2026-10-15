// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.ContentHealthDtos.ContentHealthResponse;
import eu.socle.document.ContentHealthDtos.StaleDocumentItem;
import eu.socle.user.UserSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Santé du contenu par espace — documents stale filtrés OpenFGA (même pattern Search/Graphe).
 */
@Service
public class ContentHealthService {

    private final DocumentRepository documentRepository;
    private final StalenessService stalenessService;
    private final AuthorizationService authorizationService;
    private final UserSyncService userSyncService;
    private final JdbcTemplate jdbc;

    public ContentHealthService(
            DocumentRepository documentRepository,
            StalenessService stalenessService,
            AuthorizationService authorizationService,
            UserSyncService userSyncService,
            JdbcTemplate jdbc
    ) {
        this.documentRepository = documentRepository;
        this.stalenessService = stalenessService;
        this.authorizationService = authorizationService;
        this.userSyncService = userSyncService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ContentHealthResponse forSpace(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");

        Set<UUID> viewable = new HashSet<>(
                authorizationService.listViewableDocumentIds(user.getId(), DocumentScope.space(spaceId)));
        Set<UUID> pageIds = pageDocumentIds(spaceId);
        List<DocumentEntity> inSpace = documentRepository.findActiveBySpaceId(spaceId);

        List<StaleDocumentItem> stale = new ArrayList<>();
        int viewableInSpace = 0;
        for (DocumentEntity d : inSpace) {
            if (pageIds.contains(d.getId()) || !viewable.contains(d.getId())) {
                continue;
            }
            viewableInSpace++;
            var f = stalenessService.freshness(d.getId(), d.getCreatedAt());
            if (f.stale()) {
                stale.add(new StaleDocumentItem(
                        d.getId(),
                        d.getTitle(),
                        d.getStatus(),
                        f.contentModifiedAt(),
                        f.ageDays()
                ));
            }
        }
        stale.sort((a, b) -> Long.compare(b.ageDays(), a.ageDays()));

        return new ContentHealthResponse(
                spaceId,
                stalenessService.thresholdDays(),
                viewableInSpace,
                stale.size(),
                List.copyOf(stale)
        );
    }

    private Set<UUID> pageDocumentIds(UUID spaceId) {
        if (jdbc == null) {
            return Set.of();
        }
        List<UUID> ids = jdbc.query(
                """
                SELECT page_document_id FROM folders
                 WHERE space_id = ? AND page_document_id IS NOT NULL
                """,
                (rs, i) -> (UUID) rs.getObject(1),
                spaceId);
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(ids);
    }
}
