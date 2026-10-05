// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.linkpreview;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class LinkPreviewDtos {

    private LinkPreviewDtos() {}

    public record FetchRequest(@NotBlank String url) {}

    /**
     * Carte d'aperçu. Sans fetch externe si le feature est désactivé :
     * {@code title} peut être null, {@code domain} dérivé de l'URL.
     */
    public record PreviewView(
            String url,
            String domain,
            String title,
            UUID thumbnailAttachmentId,
            boolean fetched
    ) {}
}
