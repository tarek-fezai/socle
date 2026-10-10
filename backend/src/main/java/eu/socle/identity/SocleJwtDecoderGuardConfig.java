// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Enveloppe tout {@link JwtDecoder} IdP pour rejeter les claims {@code socle_*}.
 */
@Configuration
public class SocleJwtDecoderGuardConfig {

    @Bean
    static BeanPostProcessor socleReservedClaimsJwtDecoderGuard() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof JwtDecoder decoder && !(bean instanceof SocleGuardedJwtDecoder)) {
                    return new SocleGuardedJwtDecoder(decoder, new SocleReservedClaimsValidator());
                }
                return bean;
            }
        };
    }
}
