// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.SocleBackendApplication;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = SocleBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(FolderPageHttpSuite.JwtConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class FolderPageRelationalTest extends FolderPageHttpSuite {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        register(registry, "relational", null);
    }

    static void register(DynamicPropertyRegistry registry, String provider, String gitPath) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("socle.temporal.worker-enabled", () -> "false");
        registry.add("socle.openfga.auto-init", () -> "false");
        registry.add("socle.identity.role-source", () -> "BOTH");
        registry.add("socle.identity.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:9/jwks");
        registry.add("socle.storage.provider", () -> provider);
        if (gitPath != null) {
            registry.add("socle.storage.git-repository-path", () -> gitPath);
        }
    }
}
