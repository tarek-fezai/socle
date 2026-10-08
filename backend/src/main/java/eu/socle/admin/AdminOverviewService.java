// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.admin;

import eu.socle.admin.AdminOverviewDtos.AdminOverviewView;
import eu.socle.admin.AdminOverviewDtos.OidcSummaryView;
import eu.socle.admin.AdminOverviewDtos.PlanSummaryView;
import eu.socle.identity.IdentityProperties;
import eu.socle.licence.LicenceDtos.LicenceView;
import eu.socle.licence.LicenceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOverviewService {

    private final JdbcTemplate jdbc;
    private final IdentityProperties identityProperties;
    private final LicenceService licenceService;

    public AdminOverviewService(
            JdbcTemplate jdbc,
            IdentityProperties identityProperties,
            LicenceService licenceService
    ) {
        this.jdbc = jdbc;
        this.identityProperties = identityProperties;
        this.licenceService = licenceService;
    }

    @Transactional(readOnly = true)
    public AdminOverviewView overview(Jwt jwt) {
        LicenceView licence = licenceService.get(jwt);
        String issuer = identityProperties.getIssuerUri();
        boolean connected = issuer != null && !issuer.isBlank();
        OidcSummaryView oidc = new OidcSummaryView(
                connected ? issuer.trim() : "",
                nullToEmpty(identityProperties.getClientId()),
                connected ? "connected" : "unavailable"
        );
        PlanSummaryView plan = new PlanSummaryView(
                licence.evaluationMode(),
                licence.edition(),
                licence.expiresAt()
        );
        return new AdminOverviewView(oidc, countActiveUsers(), countSpaces(), plan);
    }

    private long countActiveUsers() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status = 'active' AND COALESCE(is_system_account, false) = false",
                Long.class);
        return n == null ? 0 : n;
    }

    private long countSpaces() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM spaces WHERE deleted_at IS NULL",
                Long.class);
        return n == null ? 0 : n;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
