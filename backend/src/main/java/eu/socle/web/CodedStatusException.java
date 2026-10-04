// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * {@link ResponseStatusException} portant un {@code code} stable pour l'UI
 * (champ {@code code} du Problem Detail RFC 9457) et des propriétés optionnelles
 * (listes de champs, attributions, etc.).
 */
public class CodedStatusException extends ResponseStatusException {

    private final String code;
    private final Map<String, Object> properties;

    public CodedStatusException(HttpStatus status, String code, String reason) {
        this(status, code, reason, Map.of());
    }

    public CodedStatusException(
            HttpStatus status, String code, String reason, Map<String, Object> properties
    ) {
        super(status, reason);
        this.code = code;
        this.properties = properties == null || properties.isEmpty()
                ? Map.of()
                : Map.copyOf(properties);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
