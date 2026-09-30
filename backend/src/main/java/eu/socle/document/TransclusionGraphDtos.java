package eu.socle.document;

import java.util.List;
import java.util.UUID;

/** DTOs du graphe de dépendances de transclusion (périmètre espace). */
public final class TransclusionGraphDtos {

    private TransclusionGraphDtos() {}

    public record GraphNode(
            UUID id,
            String title,
            UUID spaceId
    ) {}

    /**
     * @param kind {@code intra} si source et cible partagent le même {@code spaceId},
     *             sinon {@code inter}
     */
    public record GraphEdge(
            UUID sourceId,
            UUID targetId,
            String kind
    ) {
        public static final String INTRA = "intra";
        public static final String INTER = "inter";
    }

    public record GraphResponse(
            UUID spaceId,
            List<GraphNode> nodes,
            List<GraphEdge> edges
    ) {}
}
