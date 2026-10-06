// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventaire hors DB : corps TipTap de {@code infra/sql/demo-seed.sql} et modèles V27.
 * Attend 0 échec.
 */
class TipTapSeedInventoryTest {

    private static final Pattern DEMO_BODY = Pattern.compile(
            "'(\\{\\\"type\\\":\\\"doc\\\".*?})'::jsonb",
            Pattern.DOTALL);
    private static final Pattern V27_BODY = Pattern.compile(
            "\\$json\\$(\\{\\\"type\\\":\\\"doc\\\".*?})\\$json\\$",
            Pattern.DOTALL);

    private final TipTapContentValidator validator = new TipTapContentValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void demoSeedAndV27Templates_allValid() throws Exception {
        Path root = resolveRepoRoot();
        String demoSql = Files.readString(root.resolve("infra/sql/demo-seed.sql"), StandardCharsets.UTF_8);
        String v27Sql = Files.readString(
                root.resolve("backend/src/main/resources/db/migration/V27__templates.sql"),
                StandardCharsets.UTF_8);

        List<Map.Entry<String, String>> bodies = new ArrayList<>();
        Matcher demo = DEMO_BODY.matcher(demoSql);
        int i = 0;
        while (demo.find()) {
            bodies.add(Map.entry("demo-seed#" + (++i), unescapeSql(demo.group(1))));
        }
        Matcher v27 = V27_BODY.matcher(v27Sql);
        int t = 0;
        while (v27.find()) {
            bodies.add(Map.entry("V27-template#" + (++t), v27.group(1)));
        }

        assertThat(bodies).as("corps extraits").hasSizeGreaterThanOrEqualTo(10);

        Map<String, String> failures = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bodies) {
            Map<String, Object> body = mapper.readValue(e.getValue(), new TypeReference<>() {});
            validator.firstError(body).ifPresent(detail -> failures.put(e.getKey(), detail));
        }

        System.out.printf(
                "TipTap inventaire seed : scanned=%d invalid=%d drafts=0 (table vide)%n",
                bodies.size(),
                failures.size());
        assertThat(failures)
                .as("corps non conformes : %s", failures)
                .isEmpty();
        // Chiffres attendus : 5 documents démo (+ 5 versions = miroir, hors extraction SQL)
        // + 5 modèles V27 = 10 corps uniques dans les sources.
        assertThat(bodies).hasSize(10);
    }

    private static String unescapeSql(String s) {
        return s.replace("''", "'");
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve("infra/sql/demo-seed.sql"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("infra/sql/demo-seed.sql"))) {
            return parent;
        }
        throw new IllegalStateException("repo root introuvable depuis " + cwd);
    }
}
