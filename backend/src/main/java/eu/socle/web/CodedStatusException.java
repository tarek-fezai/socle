// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ResponseStatusException} portant un {@code code} stable pour l'UI
 * (champ {@code code} du Problem Detail RFC 9457).
 */
public class CodedStatusException extends ResponseStatusException {

    private final String code;

    public CodedStatusException(HttpStatus status, String code, String reason) {
        super(status, reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
