package eu.socle.authz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garde-fou : {@code DocumentScope.global()} réservé à {@code DocumentService.list}.
 * {@code listViewableDocumentIds(userId, null)} interdit.
 */
class DocumentScopeGlobalUsageTest {

    @Test
    void nullScope_throwsIllegalArgument() {
        AuthorizationService authz = new AuthorizationService(null);
        assertThatThrownBy(() -> authz.listViewableDocumentIds(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DocumentScope");
    }

    @Test
    void onlyDocumentServiceUsesGlobalInMainSources() throws IOException {
        Path mainJava = findMainJavaRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("DocumentScope.java"))
                    .filter(p -> !p.getFileName().toString().equals("DocumentService.java"))
                    .forEach(p -> {
                        try {
                            String src = Files.readString(p);
                            if (src.contains("DocumentScope.global()")) {
                                offenders.add(mainJava.relativize(p).toString().replace('\\', '/'));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(offenders)
                .as("DocumentScope.global() hors DocumentService — périmètre instance interdit")
                .isEmpty();
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
