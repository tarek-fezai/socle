// SPDX-License-Identifier: AGPL-3.0-or-later
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
