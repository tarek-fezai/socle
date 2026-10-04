// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
import eu.socle.identity.AccessPolicyDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Erreurs API en RFC 9457 ({@code application/problem+json}).
 * <ul>
 *   <li>4xx {@link ResponseStatusException} : {@code detail} = raison métier ; {@code code} si présent</li>
 *   <li>5xx / inattendu : {@code detail} générique + {@code correlationId} (jamais message ni stack)</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String GENERIC_INTERNAL = "Erreur interne";

    @ExceptionHandler(CodedStatusException.class)
    public ResponseEntity<ProblemDetail> handleCoded(CodedStatusException ex, HttpServletRequest request) {
        return toProblem(ex, ex.getCode(), request);
    }

    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<ProblemDetail> handleApprovalConflict(
            ApprovalConflictException ex, HttpServletRequest request) {
        return toProblem(ex, ex.getError(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        return toProblem(ex, null, request);
    }

    /** Refus de la politique d'accès levé hors filtre (ex. sync dans un contrôleur). */
    @ExceptionHandler(AccessPolicyDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessPolicyDenied(AccessPolicyDeniedException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "access_denied");
        body.put("reason", ex.getReason().code());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request)
            throws Exception {
        // Laisser Spring Security / le 404 ressource suivre leur résolution habituelle.
        if (ex instanceof AccessDeniedException
                || ex instanceof AuthenticationException
                || ex instanceof NoResourceFoundException) {
            throw ex;
        }
        return internalError(ex, request);
    }

    private ResponseEntity<ProblemDetail> toProblem(
            ResponseStatusException ex, String code, HttpServletRequest request) {
        HttpStatusCode statusCode = ex.getStatusCode();
        int status = statusCode.value();
        if (status >= 500) {
            return internalError(ex, request, statusCode);
        }
        ProblemDetail problem = ProblemDetail.forStatus(statusCode);
        problem.setTitle(titleOf(statusCode));
        problem.setDetail(ex.getReason());
        problem.setInstance(instance(request));
        if (code != null && !code.isBlank()) {
            problem.setProperty("code", code);
        }
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ResponseEntity<ProblemDetail> internalError(Exception ex, HttpServletRequest request) {
        return internalError(ex, request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ProblemDetail> internalError(
            Exception ex, HttpServletRequest request, HttpStatusCode statusCode) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled API error correlationId={} path={}", correlationId, request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(statusCode);
        problem.setTitle(titleOf(statusCode));
        problem.setDetail(GENERIC_INTERNAL);
        problem.setInstance(instance(request));
        problem.setProperty("correlationId", correlationId);
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static URI instance(HttpServletRequest request) {
        return URI.create(request.getRequestURI());
    }

    private static String titleOf(HttpStatusCode statusCode) {
        if (statusCode instanceof HttpStatus status) {
            return status.getReasonPhrase();
        }
        return "Error";
    }
}
