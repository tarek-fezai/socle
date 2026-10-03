// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.openfga.sdk.api.client.OpenFgaClient;
import eu.socle.SocleBackendApplication;
import eu.socle.config.SecurityWebMvcTestConfig;
import eu.socle.identity.SocleRole;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.OpenAPIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Génère {@code openapi/openapi.json} à la racine du dépôt via springdoc (GET {@code /api-docs}).
 * Exécution : {@code mvn -B -Dtest=OpenApiExportIT test} depuis {@code backend/}.
 */
@SpringBootTest(classes = SocleBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(SecurityWebMvcTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class OpenApiExportIT {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.placeholder-replacement", () -> "false");
        registry.add("SOCLE_OPENAPI_ENABLED", () -> "true");
        registry.add("springdoc.api-docs.enabled", () -> "true");
        registry.add("springdoc.swagger-ui.enabled", () -> "true");
        registry.add("springdoc.enable-default-api-docs", () -> "true");
        registry.add("socle.temporal.worker-enabled", () -> "false");
        registry.add("socle.openfga.auto-init", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired OpenAPIService openAPIService;
    @Autowired SpringDocConfigProperties springDocConfigProperties;

    @MockBean WorkflowClient workflowClient;
    @MockBean OpenFgaClient openFgaClient;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    static Path resolveExportPath() {
        String override = System.getenv("OPENAPI_EXPORT_PATH");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("backend".equals(cwd.getFileName().toString())) {
            return cwd.resolve("../openapi/openapi.json").normalize();
        }
        return cwd.resolve("openapi/openapi.json").normalize();
    }

    @Test
    void exportOpenApiContractToRepo() throws Exception {
        String apiDocsPath = springDocConfigProperties.getApiDocs().getPath();
        assertThat(apiDocsPath).isNotBlank();

        String raw = fetchOpenApiJsonViaHttp(apiDocsPath);
        if (raw == null) {
            OpenAPI openAPI = openAPIService.build(Locale.ENGLISH);
            raw = Json.mapper().writeValueAsString(openAPI);
        }

        assertThat(raw).contains("openapi");

        JsonNode tree = PRETTY.readTree(raw);
        Path exportPath = resolveExportPath();
        Files.createDirectories(exportPath.getParent());
        Files.writeString(exportPath, PRETTY.writeValueAsString(tree), StandardCharsets.UTF_8);

        assertThat(exportPath).exists();
        String written = Files.readString(exportPath, StandardCharsets.UTF_8);
        assertThat(written).contains("openapi");
        assertThat(written).contains("HomeResponse");
        assertThat(written).contains("DocumentResponse");
    }

    private String fetchOpenApiJsonViaHttp(String apiDocsPath) throws Exception {
        MvcResult result = mockMvc.perform(get(apiDocsPath)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(SocleRole.ADMINISTRATEUR_SYSTEME.authority()))))
                .andReturn();
        if (result.getResponse().getStatus() == 200) {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        if (result.getResponse().getStatus() == 404 && !"/v3/api-docs".equals(apiDocsPath)) {
            result = mockMvc.perform(get("/v3/api-docs")
                            .accept(MediaType.APPLICATION_JSON)
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(SocleRole.ADMINISTRATEUR_SYSTEME.authority()))))
                    .andExpect(status().isOk())
                    .andReturn();
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        return null;
    }
}
