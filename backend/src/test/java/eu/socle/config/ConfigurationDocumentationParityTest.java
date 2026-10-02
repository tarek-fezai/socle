// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/operations/configuration.md} aligned with {@code application.yml}
 * env placeholders and Socle/OpenFGA/Temporal/CORS-related bindings.
 */
class ConfigurationDocumentationParityTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Path CONFIG_DOC = Path.of("..", "docs", "operations", "configuration.md");

    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::[^}]*)?\\}");

    /** Env names tied to {@link SocleProperties} and nested records (via application.yml). */
    private static final Set<String> SOCLE_PROPERTIES_ENV = Set.of(
            "SOCLE_INSTANCE_DISPLAY_NAME",
            "TEMPORAL_TARGET",
            "TEMPORAL_NAMESPACE",
            "OPENFGA_API_URL",
            "OPENFGA_STORE_ID",
            "OPENFGA_MODEL_ID",
            "OPENFGA_LIST_OBJECTS_MAX_RESULTS",
            "OPENFGA_MAX_CHECKS_PER_BATCH_CHECK",
            "OPENFGA_BATCH_CHECK_PARALLELISM",
            "OPENFGA_DOCUMENT_CHECK_WARN_THRESHOLD",
            "SOCLE_FGA_VISIBILITY_MIGRATION_ON_STARTUP",
            "CORS_ALLOWED_ORIGINS",
            "SOCLE_FOLDERS_MAX_DEPTH"
    );

    @Test
    void applicationYamlEnvPlaceholdersAppearInConfigurationDoc() throws IOException {
        String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
        String doc = Files.readString(CONFIG_DOC, StandardCharsets.UTF_8);

        Set<String> fromYaml = extractEnvNames(yaml);
        List<String> missingFromYaml = fromYaml.stream()
                .filter(name -> !doc.contains(name))
                .sorted()
                .collect(Collectors.toList());

        assertThat(missingFromYaml)
                .as("Add missing vars to docs/operations/configuration.md: %s", missingFromYaml)
                .isEmpty();

        List<String> missingSocleProps = SOCLE_PROPERTIES_ENV.stream()
                .filter(name -> !doc.contains(name))
                .sorted()
                .collect(Collectors.toList());

        assertThat(missingSocleProps)
                .as("SocleProperties-related env names must be documented: %s", missingSocleProps)
                .isEmpty();
    }

    private static Set<String> extractEnvNames(String yaml) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = ENV_PLACEHOLDER.matcher(yaml);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
