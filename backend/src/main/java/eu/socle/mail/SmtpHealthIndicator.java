// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.mail;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Santé SMTP sans secret (pas d'hôte utilisateur, mot de passe, ni jeton). */
public class SmtpHealthIndicator implements HealthIndicator {

    private final SmtpProperties properties;

    public SmtpHealthIndicator(SmtpProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.configured()) {
            return Health.up().withDetail("smtp", "not_configured").build();
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.host(), properties.port()), 3000);
            return Health.up()
                    .withDetail("smtp", "up")
                    .withDetail("tls", properties.tlsMode().name().toLowerCase())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("smtp", "down")
                    .withDetail("tls", properties.tlsMode().name().toLowerCase())
                    .build();
        }
    }
}
