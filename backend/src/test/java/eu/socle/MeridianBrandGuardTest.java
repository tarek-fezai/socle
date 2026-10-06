// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou rebrand : la marque legacy ne doit plus apparaître hors migrations
 * historiques et artefacts exclus.
 */
class MeridianBrandGuardTest {

    /** Construit le motif sans littéral complet dans une seule constante fragile. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "meri" + "dian",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    void noLegacyBrandOutsideAllowedPaths() throws IOException {
        Path root = findRepoRoot();
        List<String> violations = new ArrayList<>();
        for (Path scanRoot : scanRoots(root)) {
            if (!Files.isDirectory(scanRoot) && !Files.isRegularFile(scanRoot)) {
                continue;
            }
            if (Files.isRegularFile(scanRoot)) {
                checkFile(root, scanRoot, violations);
                continue;
            }
            try (Stream<Path> walk = Files.walk(scanRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !isExcluded(root, p))
                        .forEach(p -> checkFile(root, p, violations));
            }
        }
        assertThat(violations)
                .as("legacy brand string outside allowed exclusions")
                .isEmpty();
    }

    private static List<Path> scanRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("backend/src"));
        roots.add(root.resolve("backend/pom.xml"));
        roots.add(root.resolve("frontend/src"));
        Path frontend = root.resolve("frontend");
        if (Files.isDirectory(frontend)) {
            try (Stream<Path> configs = Files.list(frontend)) {
                configs.filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return Files.isRegularFile(p) && (
                            name.contains("config")
                                    || name.startsWith("vite")
                                    || name.startsWith("tsconfig")
                                    || name.startsWith("eslint")
                                    || name.equals("package.json")
                                    || name.equals("index.html")
                    );
                }).forEach(roots::add);
            } catch (IOException ignored) {
                // skip listing failures
            }
        }
        roots.add(root.resolve("docs"));
        roots.add(root.resolve("README.md"));
        roots.add(root.resolve("infra"));
        roots.add(root.resolve("webhook-worker"));
        roots.add(root.resolve("socle_schema.sql"));
        Path goMod = root.resolve("go.mod");
        if (Files.exists(goMod)) {
            roots.add(goMod);
        }
        Path workerGoMod = root.resolve("webhook-worker/go.mod");
        if (Files.exists(workerGoMod)) {
            roots.add(workerGoMod);
        }
        return roots;
    }

    private static void checkFile(Path root, Path file, List<String> violations) {
        if (isExcluded(root, file)) {
            return;
        }
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        try {
            byte[] bytes = Files.readAllBytes(file);
            // Skip likely-binary
            if (looksBinary(bytes)) {
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (FORBIDDEN.matcher(content).find()) {
                violations.add(relative);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + relative, e);
        }
    }

    private static boolean isExcluded(Path root, Path file) {
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.contains("/target/") || lower.startsWith("target/")) {
            return true;
        }
        if (lower.contains("/node_modules/") || lower.startsWith("node_modules/")) {
            return true;
        }
        if (lower.contains("/.cursor/") || lower.startsWith(".cursor/")) {
            return true;
        }
        if (lower.endsWith(".txt")) {
            return true;
        }
        if (lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".so")
                || lower.endsWith(".dylib") || lower.endsWith(".class") || lower.endsWith(".jar")) {
            return true;
        }
        if (lower.contains("/bin/") || lower.startsWith("bin/")) {
            return true;
        }
        if (lower.contains("systeme-documentation-direction-ui")) {
            return true;
        }
        // Historical Flyway V1–V21 — must not be edited; comments may still mention legacy brand.
        if (lower.matches(".*/db/migration/v\\d+__.*\\.sql")
                || lower.matches(".*\\\\db\\\\migration\\\\v\\d+__.*\\.sql")) {
            String name = file.getFileName().toString();
            if (name.matches("(?i)V([1-9]|1[0-9]|2[01])__.*\\.sql")) {
                return true;
            }
        }
        // This guard + the doc that explains historical migration comments.
        if (file.getFileName().toString().equals("MeridianBrandGuardTest.java")) {
            return true;
        }
        if (relative.equals("docs/flyway-historical-comments.md")) {
            return true;
        }
        return false;
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static Path findRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("backend/src")) && Files.isRegularFile(cwd.resolve("socle_schema.sql"))) {
            return cwd;
        }
        if (Files.isDirectory(cwd.resolve("src")) && cwd.getFileName().toString().equals("backend")) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        Path parent = cwd.getParent();
        if (parent != null
                && Files.isDirectory(parent.resolve("backend/src"))
                && Files.isRegularFile(parent.resolve("socle_schema.sql"))) {
            return parent;
        }
        throw new IllegalStateException("repo root introuvable depuis " + cwd);
    }
}
