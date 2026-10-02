// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
import eu.socle.identity.AccessPolicyDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<Map<String, String>> handleApprovalConflict(ApprovalConflictException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", ex.getError());
        body.put("message", ex.getReason() != null ? ex.getReason() : "Conflit");
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** Refus de la politique d'accès levé hors filtre (ex. sync dans un contrôleur). */
    @ExceptionHandler(AccessPolicyDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessPolicyDenied(AccessPolicyDeniedException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "access_denied");
        body.put("reason", ex.getReason().code());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
