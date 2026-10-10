// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.favorites;

import java.time.Instant;
import java.util.UUID;

public final class FavoriteDtos {

    private FavoriteDtos() {}

    public record FavoriteItem(
            String targetType,
            UUID targetId,
            Instant createdAt,
            /** Null si la cible n'est plus visible OpenFGA. */
            String title,
            String status,
            Instant updatedAt,
            UUID spaceId,
            String spaceName,
            /** Documents visibles dans l'espace favori ; null hors espace accessible. */
            Integer documentCount
    ) {
        public FavoriteItem(String targetType, UUID targetId, Instant createdAt, String title) {
            this(targetType, targetId, createdAt, title, null, null, null, null, null);
        }
    }
}
