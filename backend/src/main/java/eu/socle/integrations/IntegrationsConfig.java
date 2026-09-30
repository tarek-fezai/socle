package eu.socle.integrations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntegrationsConfig {

    @Bean
    SiemHttpClient siemHttpClient() {
        return new SiemHttpClient.Jdk();
    }
}
