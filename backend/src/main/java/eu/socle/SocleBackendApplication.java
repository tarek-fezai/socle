// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocleBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocleBackendApplication.class, args);
    }
}
