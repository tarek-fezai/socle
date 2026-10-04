// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import eu.socle.attachment.AttachmentDtos.AttachmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/documents", "/api/documents"})
public class DocumentAttachmentController {

    private final AttachmentService attachmentService;

    public DocumentAttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID documentId,
            @RequestPart("file") MultipartFile file
    ) {
        return attachmentService.upload(jwt, documentId, file);
    }
}
