// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@ConditionalOnProperty(name = "socle.openfga.enabled", havingValue = "true", matchIfMissing = true)
public class OpenFgaConfig {

    @Bean
    @Lazy
    public OpenFgaClient openFgaClient(SocleProperties properties) throws Exception {
        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(properties.openfga().apiUrl());

        if (properties.openfga().storeId() != null && !properties.openfga().storeId().isBlank()) {
            configuration = configuration.storeId(properties.openfga().storeId());
        }
        if (properties.openfga().authorizationModelId() != null
                && !properties.openfga().authorizationModelId().isBlank()) {
            configuration = configuration.authorizationModelId(properties.openfga().authorizationModelId());
        }

        return new OpenFgaClient(configuration);
    }
}
