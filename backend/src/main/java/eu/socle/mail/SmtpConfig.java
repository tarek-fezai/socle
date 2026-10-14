// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.mail;

import eu.socle.branding.BrandingMailSender;
import eu.socle.branding.LoggingBrandingMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * SMTP : TLS obligatoire hors {@code dev}/{@code local} dès que {@code SOCLE_SMTP_HOST} est posé.
 * Mot de passe / username jamais loggés.
 */
@Configuration
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpConfig {

    private static final Logger log = LoggerFactory.getLogger(SmtpConfig.class);
    static final Profiles DEV_PROFILES = Profiles.of("dev", "local");

    @Bean
    @Primary
    BrandingMailSender brandingMailSender(SmtpProperties properties, Environment environment) {
        if (!properties.configured()) {
            return new LoggingBrandingMailSender();
        }
        boolean dev = environment.acceptsProfiles(DEV_PROFILES);
        SmtpProperties.TlsMode tls = properties.tlsMode();
        if (!dev && tls == SmtpProperties.TlsMode.NONE) {
            throw new IllegalStateException(
                    "SOCLE_SMTP_TLS obligatoire (starttls|implicit) hors profil dev : démarrage refusé");
        }
        log.info("SMTP configuré port={} tls={} auth={}",
                properties.port(),
                tls,
                properties.username() != null && !properties.username().isBlank());
        return new SmtpMailSender(properties);
    }

    @Bean
    SmtpHealthIndicator smtpHealthIndicator(SmtpProperties properties) {
        return new SmtpHealthIndicator(properties);
    }
}
