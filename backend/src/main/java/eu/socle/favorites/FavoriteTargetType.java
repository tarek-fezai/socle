// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.favorites;

import java.util.Locale;

public enum FavoriteTargetType {
    DOCUMENT("document"),
    SPACE("space"),
    FOLDER("folder");

    private final String wire;

    FavoriteTargetType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static FavoriteTargetType fromPath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type requis");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (FavoriteTargetType t : values()) {
            if (t.wire.equals(key)) {
                return t;
            }
        }
        throw new IllegalArgumentException("type doit être document|space|folder");
    }
}
