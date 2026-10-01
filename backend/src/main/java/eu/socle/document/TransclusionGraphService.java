// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentLinkService.LinkRow;
import eu.socle.document.TransclusionGraphDtos.GraphEdge;
import eu.socle.document.TransclusionGraphDtos.GraphNode;
import eu.socle.document.TransclusionGraphDtos.GraphResponse;
import eu.socle.user.UserSyncService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Graphe de transclusion centré sur un espace — arêtes depuis {@code document_links}.
 * Checks OpenFGA bornés à l'espace + voisins directs ({@link DocumentScope#ids}).
 * Voir {@code docs/transclusion-graph.md}.
 */
@Service
public class TransclusionGraphService {

    private final DocumentRepository documentRepository;
    private final DocumentLinkService documentLinkService;
    private final TransclusionResolver transclusionResolver;
    private final AuthorizationService authorizationService;
    private final UserSyncService userSyncService;

    public TransclusionGraphService(
            DocumentRepository documentRepository,
            DocumentLinkService documentLinkService,
            TransclusionResolver transclusionResolver,
            AuthorizationService authorizationService,
            UserSyncService userSyncService
    ) {
        this.documentRepository = documentRepository;
        this.documentLinkService = documentLinkService;
        this.transclusionResolver = transclusionResolver;
        this.authorizationService = authorizationService;
        this.userSyncService = userSyncService;
    }

    @Transactional(readOnly = true)
    public GraphResponse graphForSpace(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");

        Set<UUID> viewable = new HashSet<>(
                authorizationService.listViewableDocumentIds(user.getId(), DocumentScope.space(spaceId)));

        List<DocumentEntity> inSpace = documentRepository.findActiveBySpaceId(spaceId);
        Map<UUID, DocumentEntity> known = new HashMap<>();
        for (DocumentEntity d : inSpace) {
            known.put(d.getId(), d);
        }

        List<LinkRow> outgoing = documentLinkService.outgoingFromSpace(spaceId);
        List<LinkRow> incoming = documentLinkService.incomingToSpace(spaceId);

        Set<UUID> neighborIds = new LinkedHashSet<>();
        for (LinkRow link : outgoing) {
            if (!known.containsKey(link.targetId())) {
                neighborIds.add(link.targetId());
            }
        }
        for (LinkRow link : incoming) {
            if (!known.containsKey(link.sourceId())) {
                neighborIds.add(link.sourceId());
            }
        }

        if (!neighborIds.isEmpty()) {
            List<UUID> allowedNeighbors = authorizationService.listViewableDocumentIds(
                    user.getId(), DocumentScope.ids(neighborIds));
            viewable.addAll(allowedNeighbors);
            if (!allowedNeighbors.isEmpty()) {
                for (DocumentEntity d : documentRepository.findAllActiveByIdIn(allowedNeighbors)) {
                    known.put(d.getId(), d);
                }
            }
        }

        LinkedHashMap<String, GraphEdge> edges = new LinkedHashMap<>();
        LinkedHashSet<UUID> nodeIds = new LinkedHashSet<>();

        for (DocumentEntity d : inSpace) {
            if (viewable.contains(d.getId())) {
                nodeIds.add(d.getId());
            }
        }

        for (LinkRow link : outgoing) {
            maybePutEdge(link, viewable, known, edges, nodeIds);
        }
        for (LinkRow link : incoming) {
            maybePutEdge(link, viewable, known, edges, nodeIds);
        }

        List<GraphNode> nodes = new ArrayList<>();
        for (UUID id : nodeIds) {
            DocumentEntity d = known.get(id);
            if (d == null) {
                d = documentRepository.findActiveById(id).orElse(null);
                if (d != null) {
                    known.put(d.getId(), d);
                }
            }
            if (d == null) {
                continue;
            }
            nodes.add(new GraphNode(d.getId(), d.getTitle(), d.getSpaceId()));
        }

        return new GraphResponse(spaceId, nodes, List.copyOf(edges.values()));
    }

    private void maybePutEdge(
            LinkRow link,
            Set<UUID> viewable,
            Map<UUID, DocumentEntity> known,
            LinkedHashMap<String, GraphEdge> edges,
            LinkedHashSet<UUID> nodeIds
    ) {
        if (!viewable.contains(link.sourceId()) || !viewable.contains(link.targetId())) {
            return;
        }
        DocumentEntity source = resolveKnown(link.sourceId(), known);
        DocumentEntity target = resolveKnown(link.targetId(), known);
        if (source == null || target == null) {
            return;
        }
        if (!transclusionResolver.allowsInterWorkspaceEdge(source.getSpaceId(), target.getSpaceId())) {
            return;
        }
        String key = source.getId() + "->" + target.getId();
        if (edges.containsKey(key)) {
            return;
        }
        String kind = source.getSpaceId().equals(target.getSpaceId())
                ? GraphEdge.INTRA
                : GraphEdge.INTER;
        edges.put(key, new GraphEdge(source.getId(), target.getId(), kind));
        nodeIds.add(source.getId());
        nodeIds.add(target.getId());
    }

    private DocumentEntity resolveKnown(UUID id, Map<UUID, DocumentEntity> known) {
        DocumentEntity cached = known.get(id);
        if (cached != null) {
            return cached;
        }
        return documentRepository.findActiveById(id).map(d -> {
            known.put(d.getId(), d);
            return d;
        }).orElse(null);
    }
}
