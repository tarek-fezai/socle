// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.attachment;

import java.time.Instant;
import java.util.UUID;

public final class AttachmentDtos {

    private AttachmentDtos() {}

    public record AttachmentResponse(
            UUID id,
            UUID documentId,
            String filename,
            String mediaType,
            long sizeBytes,
            String sha256,
            Integer width,
            Integer height,
            Instant createdAt
    ) {}
}
