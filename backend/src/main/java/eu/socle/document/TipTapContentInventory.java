// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inventaire des corps TipTap non conformes (documents, versions, brouillons, modèles).
 * Ne bloque jamais : utile au démarrage (WARN) et aux tests de seed.
 */
@Component
public class TipTapContentInventory {

    private static final Logger log = LoggerFactory.getLogger(TipTapContentInventory.class);

    private final JdbcTemplate jdbc;
    private final TipTapContentValidator validator;
    private final ObjectMapper objectMapper;

    public TipTapContentInventory(
            JdbcTemplate jdbc, TipTapContentValidator validator, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public record Finding(String kind, String id, String detail) {}

    public record Report(int scanned, int invalid, List<Finding> findings) {}

    /** Parcourt les tables métier ; journalise un WARN si invalid &gt; 0. */
    public Report scanDatabase() {
        List<Finding> findings = new ArrayList<>();
        int scanned = 0;
        scanned += scanTable(
                "document",
                "SELECT id::text AS id, body::text AS body FROM documents WHERE deleted_at IS NULL",
                findings);
        scanned += scanTable(
                "version",
                "SELECT id::text AS id, body_snapshot::text AS body FROM document_versions",
                findings);
        scanned += scanTable(
                "draft",
                "SELECT id::text AS id, body::text AS body FROM document_drafts",
                findings);
        scanned += scanTable(
                "template",
                "SELECT id::text AS id, body::text AS body FROM templates",
                findings);
        Report report = new Report(scanned, findings.size(), List.copyOf(findings));
        if (report.invalid() > 0) {
            log.warn(
                    "TipTap : {} / {} corps non conformes (documents/versions/brouillons/modèles) — "
                            + "restauration / écriture refusées avec content_invalid jusqu'à correction",
                    report.invalid(),
                    report.scanned());
            for (Finding f : findings.stream().limit(20).toList()) {
                log.warn("TipTap non conforme kind={} id={} detail={}", f.kind(), f.id(), f.detail());
            }
        } else {
            log.info("TipTap : {} corps scannés, 0 non conforme", report.scanned());
        }
        return report;
    }

    /** Valide un corps JSON déjà désérialisé (tests seed / fixtures). */
    public Optional<String> validateBody(Map<String, Object> body) {
        return validator.firstError(body);
    }

    private int scanTable(String kind, String sql, List<Finding> findings) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            for (Map<String, Object> row : rows) {
                String id = String.valueOf(row.get("id"));
                Object raw = row.get("body");
                if (raw == null) {
                    continue;
                }
                try {
                    Map<String, Object> body = objectMapper.readValue(
                            String.valueOf(raw), new TypeReference<>() {});
                    validator.firstError(body).ifPresent(detail ->
                            findings.add(new Finding(kind, id, detail)));
                } catch (Exception parse) {
                    findings.add(new Finding(kind, id, "JSON illisible : " + parse.getMessage()));
                }
            }
            return rows.size();
        } catch (Exception e) {
            log.debug("TipTap inventaire {} ignoré : {}", kind, e.getMessage());
            return 0;
        }
    }
}
