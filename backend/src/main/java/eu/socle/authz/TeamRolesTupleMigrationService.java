// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Migration FGA {@code team-roles-v1} : rôles d'équipe + réécriture
 * {@code object#R@group#member} → {@code as_*} ({@code docs/teams-and-access.md} § 1.4).
 */
@Service
public class TeamRolesTupleMigrationService {

    public static final String MIGRATION_NAME = "team-roles-v1";

    private static final Logger log = LoggerFactory.getLogger(TeamRolesTupleMigrationService.class);
    private static final List<String> OBJECT_TYPES = List.of("space", "folder", "document");
    private static final List<String> ACL = List.of("owner", "editor", "viewer");

    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TeamRolesTupleMigrationService(
            AuthorizationService authorizationService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean isApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                MIGRATION_NAME);
        return n != null && n > 0;
    }

    public MigrationReport migrate() {
        return migrate(false);
    }

    public MigrationReport migrate(boolean force) {
        if (!force && isApplied()) {
            log.info("FGA migration {} déjà appliquée — skip", MIGRATION_NAME);
            return MigrationReport.skippedReport();
        }
        MigrationReport report = run();
        persist(report);
        return report;
    }

    private MigrationReport run() {
        int members = 0;
        int rewritten = 0;
        int errors = 0;
        List<MemberRow> rows = jdbc.query(
                "SELECT group_id, user_id, role FROM group_members",
                (rs, i) -> new MemberRow(
                        (UUID) rs.getObject("group_id"),
                        (UUID) rs.getObject("user_id"),
                        rs.getString("role")));
        for (MemberRow row : rows) {
            try {
                authorizationService.grantPermission(
                        "group", row.groupId, row.role, "user", row.userId);
                members++;
            } catch (RuntimeException e) {
                errors++;
                log.warn("team-roles-v1: membre {} / {} : {}", row.groupId, row.userId, e.getMessage());
            }
        }
        for (String type : OBJECT_TYPES) {
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM " + table(type),
                    (rs, i) -> (UUID) rs.getObject("id"));
            for (UUID id : ids) {
                try {
                    rewritten += rewriteObject(type, id);
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("team-roles-v1: {} {} : {}", type, id, e.getMessage());
                }
            }
        }
        return new MigrationReport(false, members, rewritten, errors);
    }

    private int rewriteObject(String type, UUID id) {
        int n = 0;
        for (AuthorizationService.AccessTuple t : authorizationService.readTuples(type + ":" + id)) {
            if (!ACL.contains(t.relation()) || t.user() == null || !t.user().startsWith("group:")) {
                continue;
            }
            if (!t.user().endsWith("#member")) {
                continue;
            }
            String raw = t.user().substring("group:".length(), t.user().indexOf('#'));
            UUID groupId;
            try {
                groupId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                continue;
            }
            authorizationService.rewriteLegacyGroupMemberGrant(type, id, t.relation(), groupId);
            n++;
        }
        return n;
    }

    private static String table(String type) {
        if ("space".equals(type)) {
            return "spaces";
        }
        if ("folder".equals(type)) {
            return "folders";
        }
        return "documents";
    }

    private void persist(MigrationReport report) {
        String json;
        try {
            json = objectMapper.writeValueAsString(report.toMap());
        } catch (JsonProcessingException e) {
            json = "{\"migration\":\"" + MIGRATION_NAME + "\"}";
        }
        jdbc.update("""
                INSERT INTO authz_migrations (name, applied_at, report)
                VALUES (?, now(), ?::jsonb)
                ON CONFLICT (name) DO UPDATE
                  SET applied_at = now(), report = EXCLUDED.report
                """, MIGRATION_NAME, json);
    }

    public record MigrationReport(boolean skipped, int membersRewritten, int grantsRewritten, int errors) {
        static MigrationReport skippedReport() {
            return new MigrationReport(true, 0, 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skipped", skipped);
            m.put("migration", MIGRATION_NAME);
            m.put("membersRewritten", membersRewritten);
            m.put("grantsRewritten", grantsRewritten);
            m.put("errors", errors);
            return m;
        }
    }

    private record MemberRow(UUID groupId, UUID userId, String role) {}
}

@Component
class TeamRolesTupleMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamRolesTupleMigrationRunner.class);
    private final TeamRolesTupleMigrationService service;

    TeamRolesTupleMigrationRunner(TeamRolesTupleMigrationService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            service.migrate();
        } catch (RuntimeException e) {
            log.error("FGA team-roles-v1 au démarrage a échoué", e);
        }
    }
}
