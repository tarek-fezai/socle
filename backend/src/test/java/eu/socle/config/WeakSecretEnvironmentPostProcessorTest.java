// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeakSecretEnvironmentPostProcessorTest {

    @Test
    void rejectsForbiddenLiterals() {
        assertThatThrownBy(() -> WeakSecretEnvironmentPostProcessor.rejectIfWeak("OIDC_CLIENT_SECRET", "change-me"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("change-me");
        assertThatThrownBy(() -> WeakSecretEnvironmentPostProcessor.rejectIfWeak("p", "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WeakSecretEnvironmentPostProcessor.rejectIfWeak("p", "socle"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WeakSecretEnvironmentPostProcessor.rejectIfWeak("p", "  "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsStrongSecret() {
        WeakSecretEnvironmentPostProcessor.rejectIfWeak("OIDC_CLIENT_SECRET", "s3cure-R4ndom!");
    }

    @Test
    void rejectWeakSecretsIgnoresUnsetOptionalSecrets() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("socle.security.reject-weak-secrets", "true");
        env.setProperty("spring.datasource.password", "s3cure-R4ndom!");
        env.setProperty("OIDC_CLIENT_SECRET", "another-S3cret!");

        WeakSecretEnvironmentPostProcessor processor = new WeakSecretEnvironmentPostProcessor();
        assertThatCode(() -> processor.postProcessEnvironment(env, new SpringApplication()))
                .doesNotThrowAnyException();
    }
}
