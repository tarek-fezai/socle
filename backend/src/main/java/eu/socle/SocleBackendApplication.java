// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = OAuth2ClientAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SocleBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocleBackendApplication.class, args);
    }
}
