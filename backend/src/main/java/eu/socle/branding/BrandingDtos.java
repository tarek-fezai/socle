// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.branding;

import java.time.Instant;

public final class BrandingDtos {

    private BrandingDtos() {}

    /** Réponse admin {@code GET/PUT /api/v1/admin/branding}. */
    public record BrandingAdminView(
            String instanceName,
            /** {@code #RRGGBB} ou {@code null} (couleur par défaut du produit). */
            String accentColor,
            boolean hidePoweredBy,
            String senderName,
            String senderEmail,
            boolean hasLogo,
            boolean hasFavicon,
            String logoUrl,
            String faviconUrl,
            /** {@code SOCLE_PUBLIC_BASE_URL} — lecture seule (non modifiable via l'API). */
            String publicBaseUrl,
            String publicBaseUrlNote,
            Instant updatedAt
    ) {}

    /**
     * PUT = remplacement complet des 4 champs éditables ({@code null} efface accent / expéditeur ;
     * {@code hidePoweredBy} absent = {@code false}). Logo et favicon : endpoints multipart dédiés.
     */
    public record UpdateBrandingRequest(
            String accentColor,
            Boolean hidePoweredBy,
            String senderName,
            String senderEmail
    ) {}

    /** {@code GET /api/v1/public/branding} — AUCUN champ admin (ni expéditeur, ni adresse e-mail). */
    public record PublicBrandingView(
            String instanceName,
            String accentColor,
            String logoUrl,
            String faviconUrl,
            boolean hidePoweredBy
    ) {}

    /** {@code to} optionnel : par défaut l'adresse e-mail de l'administrateur connecté. */
    public record TestEmailRequest(String to) {}

    public record TestEmailResponse(String status, String to, String from, String channel) {}
}
