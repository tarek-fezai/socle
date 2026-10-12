// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Pepper HMAC des invitations ({@code SOCLE_INVITE_PEPPER}, ≥ 32 octets).
 * Hors profils {@code dev}/{@code local} : absent ou trop court ⇒ démarrage refusé.
 */
@Configuration
@EnableConfigurationProperties(InviteProperties.class)
public class InviteConfig {

    private static final Logger log = LoggerFactory.getLogger(InviteConfig.class);
    static final Profiles DEV_PROFILES = Profiles.of("dev", "local");

    @Bean
    InviteHasher inviteHasher(InviteProperties properties, Environment environment) {
        return createHasher(properties.pepper(), environment);
    }

    static InviteHasher createHasher(String pepper, Environment environment) {
        byte[] bytes = pepper == null ? new byte[0] : pepper.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 && environment.acceptsProfiles(DEV_PROFILES)) {
            byte[] ephemeral = new byte[InviteHasher.MIN_PEPPER_BYTES];
            new SecureRandom().nextBytes(ephemeral);
            log.warn("SOCLE_INVITE_PEPPER absent (profil dev) : pepper éphémère, les invitations "
                    + "seront invalidées au redémarrage");
            return new InviteHasher(ephemeral);
        }
        if (bytes.length < InviteHasher.MIN_PEPPER_BYTES) {
            throw new IllegalStateException("SOCLE_INVITE_PEPPER obligatoire (≥ " + InviteHasher.MIN_PEPPER_BYTES
                    + " octets) : démarrage refusé" + (bytes.length == 0 ? " (absent)" : " (trop court)"));
        }
        return new InviteHasher(bytes);
    }
}
