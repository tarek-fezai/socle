// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/documents/{id}/export")
    public ResponseEntity<byte[]> exportDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return toResponse(exportService.exportDocument(jwt, id));
    }

    @GetMapping("/folders/{id}/export")
    public ResponseEntity<byte[]> exportFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return toResponse(exportService.exportFolder(jwt, id));
    }

    @GetMapping("/tags/{id}/export")
    public ResponseEntity<byte[]> exportTag(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return toResponse(exportService.exportTag(jwt, id));
    }

    private static ResponseEntity<byte[]> toResponse(ExportService.ExportFile file) {
        String disposition = "attachment; filename=\""
                + file.filename().replace("\"", "")
                + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(file.filename(), StandardCharsets.UTF_8)
                        .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}
