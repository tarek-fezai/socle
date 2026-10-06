// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import eu.socle.attachment.AttachmentService.AttachmentContent;
import eu.socle.attachment.AttachmentService.AttachmentRow;
import eu.socle.blob.BlobStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private static final Pattern BYTES_RANGE = Pattern.compile(
            "^bytes=(\\d+)-(\\d*)$", Pattern.CASE_INSENSITIVE);

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) throws Exception {
        Optional<BlobStore.ByteRange> range = parseRange(rangeHeader);
        AttachmentContent content = attachmentService.openForDownload(jwt, id, range);
        AttachmentRow row = content.row();
        BlobStore.BlobObject blob = content.blob();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox");
        headers.set(HttpHeaders.ETAG, "\"" + row.sha256() + "\"");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, AttachmentService.contentDisposition(row));
        headers.setContentType(MediaType.parseMediaType(row.mediaType()));
        headers.setContentLength(blob.contentLength());

        if (blob.range().isPresent()) {
            BlobStore.ByteRange r = blob.range().get();
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + r.from() + "-" + r.to() + "/" + blob.totalSize());
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            return new ResponseEntity<>(
                    new InputStreamResource(blob.content()) {
                        @Override
                        public long contentLength() {
                            return blob.contentLength();
                        }
                    },
                    headers,
                    HttpStatus.PARTIAL_CONTENT);
        }

        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        return new ResponseEntity<>(
                new InputStreamResource(blob.content()) {
                    @Override
                    public long contentLength() {
                        return blob.contentLength();
                    }
                },
                headers,
                HttpStatus.OK);
    }

    static Optional<BlobStore.ByteRange> parseRange(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        Matcher m = BYTES_RANGE.matcher(header.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        long from = Long.parseLong(m.group(1));
        String toGroup = m.group(2);
        long to = toGroup == null || toGroup.isBlank() ? Long.MAX_VALUE - 1 : Long.parseLong(toGroup);
        if (to < from) {
            return Optional.empty();
        }
        return Optional.of(new BlobStore.ByteRange(from, to));
    }
}
