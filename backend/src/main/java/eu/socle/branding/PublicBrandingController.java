// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.branding;

import eu.socle.branding.BrandingDtos.PublicBrandingView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

/**
 * Branding public (page de connexion, en-tête) — sans authentification, uniquement
 * {@code instanceName, accentColor, logoUrl, faviconUrl, hidePoweredBy}.
 */
@RestController
@RequestMapping("/api/v1/public/branding")
public class PublicBrandingController {

    private final BrandingService service;

    public PublicBrandingController(BrandingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PublicBrandingView> get() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic().mustRevalidate())
                .body(service.publicView());
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return image(service.openPublicImage(false), ifNoneMatch);
    }

    @GetMapping("/favicon")
    public ResponseEntity<byte[]> favicon(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return image(service.openPublicImage(true), ifNoneMatch);
    }

    private static ResponseEntity<byte[]> image(Optional<BrandingService.PublicImage> image, String ifNoneMatch) {
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BrandingService.PublicImage img = image.get();
        CacheControl cache = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
        if (img.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(img.etag()).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.mediaType()))
                .eTag(img.etag())
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(img.bytes());
    }
}
