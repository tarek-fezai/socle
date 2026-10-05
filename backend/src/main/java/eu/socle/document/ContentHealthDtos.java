// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContentHealthDtos {

    private ContentHealthDtos() {}

    public record StaleDocumentItem(
            UUID id,
            String title,
            String status,
            Instant contentModifiedAt,
            long ageDays
    ) {}

    public record ContentHealthResponse(
            UUID spaceId,
            int thresholdDays,
            int viewableDocumentCount,
            int staleCount,
            List<StaleDocumentItem> staleDocuments
    ) {}
}
