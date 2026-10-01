// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SearchDtos {

    private SearchDtos() {}

    public record SearchHit(
            UUID id,
            String title,
            String excerpt,
            UUID spaceId,
            String spaceName,
            String docType,
            String status,
            Instant updatedAt,
            double rank
    ) {}

    /**
     * @param total estimation (présélection SQL avant Check OpenFGA) — voir {@code totalIsEstimate}
     * @param totalIsEstimate toujours {@code true} : le total compte la présélection, pas le Check final
     * @param warning non null si la page n'a pas pu être complétée après 3 itérations de refill
     */
    public record SearchResponse(
            String query,
            List<SearchHit> results,
            int total,
            boolean totalIsEstimate,
            String warning
    ) {
        public SearchResponse(String query, List<SearchHit> results, int total) {
            this(query, results, total, true, null);
        }
    }
}
