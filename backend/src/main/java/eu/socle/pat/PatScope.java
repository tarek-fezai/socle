// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Portée d'un jeton d'accès personnel. */
public enum PatScope {
    /** GET / HEAD / OPTIONS uniquement. */
    READ("read"),
    READ_WRITE("read_write");

    private final String value;

    PatScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PatScope fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (PatScope s : values()) {
            if (s.value.equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("portée inconnue : " + raw);
    }
}
