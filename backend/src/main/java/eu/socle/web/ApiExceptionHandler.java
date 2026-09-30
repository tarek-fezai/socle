package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
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
}
