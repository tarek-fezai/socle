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
            String title
    ) {}
}
