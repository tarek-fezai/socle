// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.config.SocleProperties;
import eu.socle.identity.IdentityFacade;
import eu.socle.retention.RetentionDtos.RetentionPolicy;
import eu.socle.retention.RetentionDtos.RetentionSettingsView;
import eu.socle.retention.RetentionDtos.UpdateRetentionRequest;
import eu.socle.user.UserEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Politique de rétention d'instance ({@code GET/PUT /api/v1/admin/retention}) —
 * réservée {@code ADMINISTRATEUR_SYSTEME}, chaque modification est auditée.
 */
@Service
public class RetentionSettingsService {

    public static final String MODE_UNLIMITED = "unlimited";
    public static final String MODE_MONTHS = "months";
    public static final String MODE_COUNT = "count";
    private static final Set<String> MODES = Set.of(MODE_UNLIMITED, MODE_MONTHS, MODE_COUNT);

    public static final int DEFAULT_AUDIT_RETENTION_MONTHS = 24;
    public static final int DEFAULT_ARCHIVED_DOCS_RETENTION_YEARS = 7;

    private final JdbcTemplate jdbc;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;
    private final SocleProperties properties;
    private final Clock clock;

    @Autowired
    public RetentionSettingsService(
            JdbcTemplate jdbc,
            IdentityFacade identityFacade,
            AuditService auditService,
            SocleProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RetentionSettingsView get(Jwt jwt) {
        requireAdmin(jwt);
        return view();
    }

    @Transactional
    public RetentionSettingsView update(Jwt jwt, UpdateRetentionRequest request) {
        UserEntity admin = requireAdmin(jwt);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corps requis");
        }
        RetentionPolicy before = currentPolicy();
        RetentionPolicy next = validate(request);

        jdbc.update("""
                INSERT INTO instance_settings (
                    id, audit_retention_months, version_retention_mode, version_retention_value,
                    archived_docs_retention_years, processing_register_reviewed_at, updated_at)
                VALUES (true, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE
                  SET audit_retention_months = EXCLUDED.audit_retention_months,
                      version_retention_mode = EXCLUDED.version_retention_mode,
                      version_retention_value = EXCLUDED.version_retention_value,
                      archived_docs_retention_years = EXCLUDED.archived_docs_retention_years,
                      processing_register_reviewed_at = EXCLUDED.processing_register_reviewed_at,
                      updated_at = now()
                """,
                next.auditRetentionMonths(),
                next.versionRetentionMode(),
                next.versionRetentionValue(),
                next.archivedDocsRetentionYears(),
                next.processingRegisterReviewedAt() == null ? null : Date.valueOf(next.processingRegisterReviewedAt()));

        Map<String, Object> changes = diff(before, next);
        auditService.record(admin.getId(), false, AuditActions.RETENTION_SETTINGS_UPDATED,
                "instance_settings", null, changes, null);
        if (!Objects.equals(before.processingRegisterReviewedAt(), next.processingRegisterReviewedAt())) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("from", before.processingRegisterReviewedAt() == null
                    ? null : before.processingRegisterReviewedAt().toString());
            meta.put("to", next.processingRegisterReviewedAt() == null
                    ? null : next.processingRegisterReviewedAt().toString());
            auditService.record(admin.getId(), false, AuditActions.RETENTION_PROCESSING_REGISTER_REVIEWED,
                    "instance_settings", null, meta, null);
        }
        return view();
    }

    /** Lecture interne (purge planifiée) — aucune exigence d'authentification. */
    @Transactional(readOnly = true)
    public RetentionPolicy currentPolicy() {
        List<RetentionPolicy> rows = jdbc.query("""
                SELECT audit_retention_months, version_retention_mode, version_retention_value,
                       archived_docs_retention_years, processing_register_reviewed_at
                  FROM instance_settings WHERE id = true
                """,
                (rs, i) -> {
                    Date reviewed = rs.getDate("processing_register_reviewed_at");
                    return new RetentionPolicy(
                            rs.getInt("audit_retention_months"),
                            rs.getString("version_retention_mode"),
                            (Integer) rs.getObject("version_retention_value"),
                            rs.getInt("archived_docs_retention_years"),
                            reviewed == null ? null : reviewed.toLocalDate());
                });
        if (rows.isEmpty()) {
            return new RetentionPolicy(
                    DEFAULT_AUDIT_RETENTION_MONTHS, MODE_UNLIMITED, null,
                    DEFAULT_ARCHIVED_DOCS_RETENTION_YEARS, null);
        }
        return rows.getFirst();
    }

    private RetentionSettingsView view() {
        RetentionPolicy p = currentPolicy();
        Timestamp updated = jdbc.query(
                "SELECT updated_at FROM instance_settings WHERE id = true",
                rs -> rs.next() ? rs.getTimestamp(1) : null);
        Timestamp lastPurge = jdbc.queryForObject(
                "SELECT max(created_at) FROM audit_log_events WHERE action = ?",
                Timestamp.class, AuditActions.RETENTION_PURGE_RAN);
        Long holds = jdbc.queryForObject(
                "SELECT count(*) FROM legal_holds WHERE released_at IS NULL", Long.class);
        SocleProperties.Instance instance = properties == null ? null : properties.instance();
        return new RetentionSettingsView(
                p.auditRetentionMonths(),
                p.versionRetentionMode(),
                p.versionRetentionValue(),
                p.archivedDocsRetentionYears(),
                p.processingRegisterReviewedAt(),
                instance == null ? null : instance.effectiveDataResidenceLabel(),
                holds == null ? 0 : holds,
                lastPurge == null ? null : lastPurge.toInstant(),
                updated == null ? null : updated.toInstant());
    }

    private RetentionPolicy validate(UpdateRetentionRequest r) {
        int audit = requireRange("auditRetentionMonths", r.auditRetentionMonths(), 1, 1200);
        int years = requireRange("archivedDocsRetentionYears", r.archivedDocsRetentionYears(), 1, 100);

        String mode = r.versionRetentionMode() == null
                ? null : r.versionRetentionMode().trim().toLowerCase(java.util.Locale.ROOT);
        if (mode == null || !MODES.contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "versionRetentionMode invalide (unlimited|months|count)");
        }
        Integer value = null;
        if (MODE_MONTHS.equals(mode)) {
            value = requireRange("versionRetentionValue", r.versionRetentionValue(), 1, 1200);
        } else if (MODE_COUNT.equals(mode)) {
            value = requireRange("versionRetentionValue", r.versionRetentionValue(), 1, 100_000);
        }

        LocalDate reviewed = r.processingRegisterReviewedAt();
        if (reviewed != null && reviewed.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "processingRegisterReviewedAt ne peut pas être dans le futur");
        }
        return new RetentionPolicy(audit, mode, value, years, reviewed);
    }

    private static int requireRange(String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " requis entre " + min + " et " + max);
        }
        return value;
    }

    private static Map<String, Object> diff(RetentionPolicy before, RetentionPolicy after) {
        Map<String, Object> meta = new LinkedHashMap<>();
        put(meta, "auditRetentionMonths", before.auditRetentionMonths(), after.auditRetentionMonths());
        put(meta, "versionRetentionMode", before.versionRetentionMode(), after.versionRetentionMode());
        put(meta, "versionRetentionValue", before.versionRetentionValue(), after.versionRetentionValue());
        put(meta, "archivedDocsRetentionYears",
                before.archivedDocsRetentionYears(), after.archivedDocsRetentionYears());
        put(meta, "processingRegisterReviewedAt",
                before.processingRegisterReviewedAt() == null ? null : before.processingRegisterReviewedAt().toString(),
                after.processingRegisterReviewedAt() == null ? null : after.processingRegisterReviewedAt().toString());
        return meta;
    }

    private static void put(Map<String, Object> meta, String key, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", from);
            change.put("to", to);
            meta.put(key, change);
        }
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }

    /** Instant courant (tests). */
    Instant now() {
        return clock.instant();
    }
}
