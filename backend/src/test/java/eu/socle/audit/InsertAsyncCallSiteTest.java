// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : seul {@link AuditService} appelle {@code insertAsync}.
 *
 * <p>Analyse des sources, comme {@code IdentityClaimUsageArchitectureTest} :
 * un seul site d'appel textuel, sans dépendance ArchUnit. Le cas négatif est
 * une source fictive passée au même contrôle ; aucune classe fautive n'est
 * compilée dans le dépôt.
 */
class InsertAsyncCallSiteTest {

    private static final Pattern CALL = Pattern.compile("\\binsertAsync\\s*\\(");

    @Test
    void onlyAuditServiceCallsInsertAsync() throws IOException {
        Path mainJava = findMainJavaRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        String relative = mainJava.relativize(path).toString().replace('\\', '/');
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            violations.addAll(insertAsyncCallers(relative, source));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(violations)
                .as("insertAsync hors AuditService")
                .isEmpty();
    }

    @Test
    void foreignSourceIsRejected() {
        String source = """
                class NotAuditService {
                  void leak(AuditService audit) {
                    audit.insertAsync(null, true, "x", "user", null, null, null);
                  }
                }
                """;
        assertThat(insertAsyncCallers("eu/socle/audit/NotAuditService.java", source))
                .containsExactly("eu/socle/audit/NotAuditService.java");
    }

    @Test
    void auditServiceItselfIsAllowed() {
        String source = """
                class AuditService {
                  void dispatch() {
                    insertAsync(null, true, "a", "user", null, null, null);
                  }
                }
                """;
        assertThat(insertAsyncCallers("eu/socle/audit/AuditService.java", source)).isEmpty();
    }

    static List<String> insertAsyncCallers(String relativePath, String source) {
        if (relativePath.endsWith("AuditService.java")) {
            return List.of();
        }
        if (CALL.matcher(source).find()) {
            return List.of(relativePath);
        }
        return List.of();
    }

    private static Path findMainJavaRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = cwd.resolve("backend/src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("src/main/java introuvable depuis " + cwd);
    }
}
