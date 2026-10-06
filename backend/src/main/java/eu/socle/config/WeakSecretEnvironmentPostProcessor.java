// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Locale;
import java.util.Set;

/**
 * Refuse de démarrer si un secret défini vaut {@code change-me}, {@code admin}, {@code socle}
 * ou est vide. Activé via {@code socle.security.reject-weak-secrets=true}
 * (image prod / compose production).
 *
 * <p>Les clés absentes ne provoquent pas d'échec — seules les valeurs résolues sont contrôlées.
 */
public class WeakSecretEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(WeakSecretEnvironmentPostProcessor.class);

    private static final Set<String> FORBIDDEN = Set.of("change-me", "admin", "socle");

    private static final Set<String> SECRET_KEYS = Set.of(
            "spring.datasource.password",
            "SPRING_DATASOURCE_PASSWORD",
            "spring.security.oauth2.client.registration.keycloak.client-secret",
            "OIDC_CLIENT_SECRET",
            "POSTGRES_PASSWORD"
    );

    private static final Set<String> ALWAYS_VALIDATE_WHEN_RESOLVED = Set.of(
            "spring.datasource.password",
            "SPRING_DATASOURCE_PASSWORD",
            "OIDC_CLIENT_SECRET",
            "spring.security.oauth2.client.registration.keycloak.client-secret"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean reject = Boolean.parseBoolean(
                environment.getProperty("socle.security.reject-weak-secrets",
                        environment.getProperty("SOCLE_REJECT_WEAK_SECRETS", "false")));
        if (!reject) {
            return;
        }

        for (String key : SECRET_KEYS) {
            String value = environment.getProperty(key);
            if (value == null) {
                continue;
            }
            if (ALWAYS_VALIDATE_WHEN_RESOLVED.contains(key)) {
                rejectIfWeak(key, value);
            } else if (!value.isBlank()) {
                rejectIfWeak(key, value);
            }
        }

        log.debug("Contrôle des secrets faibles : OK");
    }

    static void rejectIfWeak(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Secret obligatoire vide : " + key
                            + " — définir une valeur forte (voir docs/operations/configuration.md)");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(normalized)) {
            throw new IllegalStateException(
                    "Secret interdit (« " + normalized + " ») pour " + key
                            + " — valeurs change-me / admin / socle / vides refusées");
        }
    }
}
