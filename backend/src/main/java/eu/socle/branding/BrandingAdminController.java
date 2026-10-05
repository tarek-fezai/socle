// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import eu.socle.branding.BrandingDtos.BrandingAdminView;
import eu.socle.branding.BrandingDtos.TestEmailRequest;
import eu.socle.branding.BrandingDtos.TestEmailResponse;
import eu.socle.branding.BrandingDtos.UpdateBrandingRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Administration du branding — {@code /api/v1/admin/**} (ADMINISTRATEUR_SYSTEME). */
@RestController
@RequestMapping("/api/v1/admin/branding")
public class BrandingAdminController {

    private final BrandingService service;

    public BrandingAdminController(BrandingService service) {
        this.service = service;
    }

    @GetMapping
    public BrandingAdminView get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt);
    }

    @PutMapping
    public BrandingAdminView update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateBrandingRequest request
    ) {
        return service.update(jwt, request);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BrandingAdminView uploadLogo(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return service.uploadLogo(jwt, file);
    }

    @DeleteMapping("/logo")
    public BrandingAdminView removeLogo(@AuthenticationPrincipal Jwt jwt) {
        return service.removeLogo(jwt);
    }

    @PostMapping(value = "/favicon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BrandingAdminView uploadFavicon(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return service.uploadFavicon(jwt, file);
    }

    @DeleteMapping("/favicon")
    public BrandingAdminView removeFavicon(@AuthenticationPrincipal Jwt jwt) {
        return service.removeFavicon(jwt);
    }

    @PostMapping("/test-email")
    public TestEmailResponse testEmail(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) TestEmailRequest request
    ) {
        return service.sendTestEmail(jwt, request == null ? null : request.to());
    }
}
