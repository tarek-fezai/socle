// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Pepper HMAC des jetons d'accès personnels ({@code SOCLE_PAT_PEPPER}, ≥ 32 octets).
 * Hors profils {@code dev}/{@code local} : absent ou trop court ⇒ démarrage refusé.
 */
@Configuration
public class PatConfig {

    private static final Logger log = LoggerFactory.getLogger(PatConfig.class);
    static final Profiles DEV_PROFILES = Profiles.of("dev", "local");

    @Bean
    PatHasher patHasher(@Value("${socle.pat.pepper:}") String pepper, Environment environment) {
        return createHasher(pepper, environment);
    }

    static PatHasher createHasher(String pepper, Environment environment) {
        byte[] bytes = pepper == null ? new byte[0] : pepper.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 && environment.acceptsProfiles(DEV_PROFILES)) {
            byte[] ephemeral = new byte[PatHasher.MIN_PEPPER_BYTES];
            new SecureRandom().nextBytes(ephemeral);
            log.warn("SOCLE_PAT_PEPPER absent (profil dev) : pepper éphémère, les jetons d'accès personnels "
                    + "seront invalidés au redémarrage");
            return new PatHasher(ephemeral);
        }
        if (bytes.length < PatHasher.MIN_PEPPER_BYTES) {
            throw new IllegalStateException("SOCLE_PAT_PEPPER obligatoire (≥ " + PatHasher.MIN_PEPPER_BYTES
                    + " octets) : démarrage refusé" + (bytes.length == 0 ? " (absent)" : " (trop court)"));
        }
        return new PatHasher(bytes);
    }
}
