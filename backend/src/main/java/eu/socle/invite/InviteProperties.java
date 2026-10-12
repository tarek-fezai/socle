// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "socle.invite")
public record InviteProperties(
        String pepper,
        int defaultTtlDays,
        int maxTtlDays,
        int resendMinIntervalSeconds,
        String trustedIssuers
) {
    public List<String> trustedIssuerList(String identityIssuerUri) {
        List<String> out = new ArrayList<>();
        if (identityIssuerUri != null && !identityIssuerUri.isBlank()) {
            out.add(normalizeIssuer(identityIssuerUri));
        }
        if (trustedIssuers != null && !trustedIssuers.isBlank()) {
            for (String part : trustedIssuers.split(",")) {
                String n = normalizeIssuer(part);
                if (!n.isEmpty() && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return List.copyOf(out);
    }

    static String normalizeIssuer(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public boolean issuerTrusted(String jwtIssuer, String identityIssuerUri) {
        String n = normalizeIssuer(jwtIssuer);
        if (n.isEmpty()) {
            return false;
        }
        for (String allowed : trustedIssuerList(identityIssuerUri)) {
            if (allowed.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    public int ttlDays() {
        int d = defaultTtlDays <= 0 ? 7 : defaultTtlDays;
        int max = maxTtlDays <= 0 ? 30 : maxTtlDays;
        return Math.min(d, max);
    }
}
