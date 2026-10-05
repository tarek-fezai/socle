// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BrandingConfig {

    @Bean
    @ConditionalOnMissingBean(BrandingMailSender.class)
    BrandingMailSender loggingBrandingMailSender() {
        return new LoggingBrandingMailSender();
    }
}
