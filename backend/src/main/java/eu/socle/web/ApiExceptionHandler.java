// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
import eu.socle.identity.AccessPolicyDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Erreurs API en RFC 9457 ({@code application/problem+json}).
 * <ul>
 *   <li>4xx métier ({@link ResponseStatusException}) : {@code detail} = raison ; {@code code} si présent</li>
 *   <li>4xx MVC / {@link ErrorResponse} : statut d'origine, {@code detail} générique sûr (jamais le message brut)</li>
 *   <li>5xx / inattendu : {@code detail} générique + {@code correlationId} (jamais message ni stack)</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String GENERIC_INTERNAL = "Erreur interne";

    @ExceptionHandler(CodedStatusException.class)
    public ResponseEntity<ProblemDetail> handleCoded(CodedStatusException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = toBusinessProblem(ex, ex.getCode(), request);
        ProblemDetail body = response.getBody();
        if (body != null && !ex.getProperties().isEmpty()) {
            ex.getProperties().forEach(body::setProperty);
        }
        return response;
    }

    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<ProblemDetail> handleApprovalConflict(
            ApprovalConflictException ex, HttpServletRequest request) {
        return toBusinessProblem(ex, ex.getError(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        return toBusinessProblem(ex, null, request);
    }

    /** Refus de la politique d'accès levé hors filtre (ex. sync dans un contrôleur). */
    @ExceptionHandler(AccessPolicyDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessPolicyDenied(AccessPolicyDeniedException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "access_denied");
        body.put("reason", ex.getReason().code());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Filet : {@link ErrorResponse} MVC non couvert par un override, puis inattendus → 500.
     * Les exceptions Security restent hors de ce conseil.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request)
            throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        if (ex instanceof MaxUploadSizeExceededException upload) {
            return uploadTooLarge(upload, request);
        }
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is4xxClientError()) {
                return clientError(ex, status, safeDetailFor(ex), null, request);
            }
            return internalError(ex, request, status);
        }
        return internalError(ex, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ResponseEntity<ProblemDetail> body = uploadTooLarge(ex, servletRequest(request));
        return ResponseEntity.status(body.getStatusCode())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body.getBody());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        HttpServletRequest servletRequest = servletRequest(request);
        if (statusCode.is5xxServerError()) {
            ResponseEntity<ProblemDetail> internal = internalError(ex, servletRequest, statusCode);
            return ResponseEntity.status(internal.getStatusCode())
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(internal.getBody());
        }
        String code = ex instanceof MaxUploadSizeExceededException ? ApiErrors.PAYLOAD_TOO_LARGE : null;
        ResponseEntity<ProblemDetail> problem =
                clientError(ex, statusCode, safeDetailFor(ex), code, servletRequest);
        return ResponseEntity.status(problem.getStatusCode())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem.getBody());
    }

    private ResponseEntity<ProblemDetail> uploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return clientError(
                ex,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Fichier trop volumineux",
                ApiErrors.PAYLOAD_TOO_LARGE,
                request);
    }

    private ResponseEntity<ProblemDetail> toBusinessProblem(
            ResponseStatusException ex, String code, HttpServletRequest request) {
        HttpStatusCode statusCode = ex.getStatusCode();
        if (statusCode.is5xxServerError()) {
            return internalError(ex, request, statusCode);
        }
        return clientError(ex, statusCode, ex.getReason(), code, request);
    }

    private ResponseEntity<ProblemDetail> clientError(
            Exception ex,
            HttpStatusCode statusCode,
            String detail,
            @Nullable String code,
            HttpServletRequest request
    ) {
        log.debug("API client error status={} path={} type={}",
                statusCode.value(), request.getRequestURI(), ex.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatus(statusCode);
        problem.setTitle(titleOf(statusCode));
        problem.setDetail(detail);
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

    /** Détails sûrs pour les erreurs MVC — jamais le message Jackson/Spring brut. */
    static String safeDetailFor(Exception ex) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            String name = mismatch.getName();
            return name != null && !name.isBlank() ? "Paramètre invalide : " + name : "Paramètre invalide";
        }
        if (ex instanceof TypeMismatchException typeMismatch && !(ex instanceof MethodArgumentTypeMismatchException)) {
            Object property = typeMismatch.getPropertyName();
            if (property != null && !property.toString().isBlank()) {
                return "Paramètre invalide : " + property;
            }
            return "Paramètre invalide";
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return "Paramètre manquant : " + missing.getParameterName();
        }
        if (ex instanceof MissingPathVariableException missingPath) {
            return "Paramètre invalide : " + missingPath.getVariableName();
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "Corps de requête illisible";
        }
        if (ex instanceof MethodArgumentNotValidException || ex instanceof BindException) {
            return "Requête invalide";
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return "Méthode non autorisée";
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return "Type de contenu non supporté";
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return "Type de réponse non acceptable";
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return "Fichier trop volumineux";
        }
        if (ex instanceof NoResourceFoundException) {
            return "Ressource introuvable";
        }
        return "Requête invalide";
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest();
        }
        throw new IllegalStateException("WebRequest non servlet");
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
