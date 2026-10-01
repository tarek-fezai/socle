// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke léger sans lever tout le contexte Spring (infra Keycloak/OpenFGA/Temporal/Postgres
 * non disponibles en surefire). L'intégration auth→authz→workflow est couverte par
 * {@link eu.socle.document.DocumentApprovalSubmitIntegrationTest}.
 */
class SocleBackendApplicationTests {

    @Test
    void moduleLoads() {
        assertThat(SocleBackendApplication.class.getSimpleName()).isEqualTo("SocleBackendApplication");
    }
}
