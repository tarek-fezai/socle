package eu.socle.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : {@code jwt.getClaim(...)} et {@code realm_access} ne doivent apparaître
 * que dans {@link IdentityClaimsMapper} (et la valeur par défaut de config).
 */
class IdentityClaimUsageArchitectureTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void getClaimAndRealmAccess_onlyInAllowedFiles() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        String relative = MAIN_JAVA.relativize(path).toString().replace('\\', '/');
                        if (relative.endsWith("IdentityClaimsMapper.java")) {
                            return;
                        }
                        if (relative.endsWith("IdentityProperties.java")) {
                            // défaut rolesClaim = realm_access.roles
                            checkGetClaimOnly(path, relative, violations);
                            return;
                        }
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("getClaim(")) {
                                violations.add(relative + ": getClaim(");
                            }
                            if (source.contains("realm_access")) {
                                violations.add(relative + ": realm_access");
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(violations)
                .as("JWT claim coupling outside IdentityClaimsMapper")
                .isEmpty();
    }

    private static void checkGetClaimOnly(Path path, String relative, List<String> violations) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("getClaim(")) {
                violations.add(relative + ": getClaim(");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
