// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "socle.smtp")
public record SmtpProperties(
        String host,
        int port,
        String username,
        String password,
        String from,
        String tls
) {
    public boolean configured() {
        return host != null && !host.isBlank();
    }

    public TlsMode tlsMode() {
        if (tls == null || tls.isBlank() || "none".equalsIgnoreCase(tls.trim())) {
            return TlsMode.NONE;
        }
        if ("starttls".equalsIgnoreCase(tls.trim())) {
            return TlsMode.STARTTLS;
        }
        if ("implicit".equalsIgnoreCase(tls.trim())) {
            return TlsMode.IMPLICIT;
        }
        throw new IllegalStateException("SOCLE_SMTP_TLS invalide (starttls|implicit|none) : " + tls);
    }

    public enum TlsMode {
        STARTTLS,
        IMPLICIT,
        NONE
    }
}
