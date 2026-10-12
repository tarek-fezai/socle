// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Migration FGA {@code team-roles-v1} : rôles d'équipe + réécriture
 * {@code object#R@group#member} → {@code as_*} et
 * {@code group#member@user} → {@code group#{rôle}@user}
 * ({@code docs/teams-and-access.md} § 1.4).
 *
 * <p>Ordre de déploiement du modèle : lecture / réécriture sous
 * {@code openfga/model-pre-team-roles.json} (sujets {@code group#member}
 * encore autorisés), puis publication de {@code openfga/model.json} une fois
 * la migration marquée appliquée. Si {@code errors > 0} ou des tuples legacy
 * restent : pas d'{@code authz_migrations}, démarrage refusé, rejouable.
 */
@Service
public class TeamRolesTupleMigrationService {

    public static final String MIGRATION_NAME = "team-roles-v1";

    /** Verrou session Postgres — même clé sur toutes les instances. */
    static final long ADVISORY_LOCK_ID = 0x534F434C_54524C31L;

    private static final Logger log = LoggerFactory.getLogger(TeamRolesTupleMigrationService.class);
    private static final List<String> RESOURCE_TYPES =
            List.of("organisation", "space", "folder", "document");
    private static final Set<String> ACL = Set.of("owner", "editor", "viewer");
    private static final ObjectMapper MODEL_MAPPER = new ObjectMapper();

    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OpenFgaClient openFgaClient;
    private final Resource previousModel;
    private final Resource currentModel;

    public TeamRolesTupleMigrationService(
            AuthorizationService authorizationService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            OpenFgaClient openFgaClient,
            @Value("classpath:openfga/model-pre-team-roles.json") Resource previousModel,
            @Value("classpath:openfga/model.json") Resource currentModel
    ) {
        this.authorizationService = authorizationService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.openFgaClient = openFgaClient;
        this.previousModel = previousModel;
        this.currentModel = currentModel;
    }

    public boolean isApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                MIGRATION_NAME);
        return n != null && n > 0;
    }

    /**
     * Démarrage : verrou, modèle précédent, réécriture, contrôle leftover,
     * persist uniquement si zéro erreur, puis modèle courant.
     */
    public MigrationReport migrateAtStartup() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException(
                    "team-roles-v1 : DataSource absente — impossible de poser pg_advisory_lock");
        }
        try (Connection lockCon = ds.getConnection()) {
            lock(lockCon);
            try {
                return migrateUnderLock();
            } finally {
                unlock(lockCon);
            }
        } catch (TeamRolesMigrationFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new TeamRolesMigrationFailedException(
                    "team-roles-v1 a échoué : " + e.getMessage(), e, 1, List.of("lock/io"));
        }
    }

    public MigrationReport migrate() {
        return migrateAtStartup();
    }

    public MigrationReport migrate(boolean force) {
        if (!force && isApplied()) {
            log.info("FGA migration {} déjà appliquée — skip", MIGRATION_NAME);
            return MigrationReport.skippedReport();
        }
        return migrateAtStartup();
    }

    private MigrationReport migrateUnderLock() {
        if (isApplied()) {
            log.info("FGA migration {} déjà appliquée — skip", MIGRATION_NAME);
            return MigrationReport.skippedReport();
        }
        publishModel(previousModel, "précédent (group#member sujets)");
        MigrationReport report = run();
        Leftover leftover = countLeftover();
        List<String> failed = new ArrayList<>(report.failedObjects());
        failed.addAll(leftover.samples());
        int errors = report.errors();
        if (leftover.total() > 0) {
            errors++;
            log.error(
                    "team-roles-v1 : {} tuple(s) legacy restant(s) "
                            + "(O#R@group#member ou group#member@user)",
                    leftover.total());
        }
        MigrationReport complete = new MigrationReport(
                false,
                report.membersRewritten(),
                report.grantsRewritten(),
                errors,
                leftover.total(),
                List.copyOf(new LinkedHashSet<>(failed)));
        if (complete.errors() > 0 || leftover.total() > 0) {
            throw failClosed(complete);
        }
        persist(complete);
        publishModel(currentModel, "courant (sans group#member sujets ACL)");
        return complete;
    }

    private TeamRolesMigrationFailedException failClosed(MigrationReport report) {
        List<String> objects = report.failedObjects();
        String preview = objects.isEmpty()
                ? "(aucun identifiant d'objet collecté)"
                : String.join(", ", objects.subList(0, Math.min(20, objects.size())));
        String msg = "team-roles-v1 a échoué : "
                + report.errors() + " erreur(s), "
                + report.leftoverLegacy() + " tuple(s) legacy restant(s), "
                + "objets concernés : " + preview
                + ". Non marquée appliquée — rejouable au prochain démarrage.";
        log.error(msg);
        return new TeamRolesMigrationFailedException(msg, report.errors(), objects);
    }

    private MigrationReport run() {
        int members = 0;
        int rewritten = 0;
        int errors = 0;
        List<String> failed = new ArrayList<>();
        List<MemberRow> rows = jdbc.query(
                "SELECT group_id, user_id, role FROM group_members",
                (rs, i) -> new MemberRow(
                        (UUID) rs.getObject("group_id"),
                        (UUID) rs.getObject("user_id"),
                        rs.getString("role")));
        Set<String> sqlMembers = new LinkedHashSet<>();
        for (MemberRow row : rows) {
            String key = row.groupId + "|" + row.userId;
            sqlMembers.add(key);
            try {
                authorizationService.rewriteLegacyGroupMemberUser(row.groupId, row.userId, row.role);
                members++;
            } catch (RuntimeException e) {
                errors++;
                failed.add("group:" + row.groupId + "#member@user:" + row.userId);
                log.warn("team-roles-v1: membre {} / {} : {}", row.groupId, row.userId, e.getMessage());
            }
        }
        List<AuthorizationService.AccessTuple> snapshot;
        try {
            snapshot = authorizationService.readAllTuplesPaged();
        } catch (RuntimeException e) {
            errors++;
            failed.add("store:*");
            log.warn("team-roles-v1: lecture store : {}", e.getMessage());
            return new MigrationReport(false, members, rewritten, errors, 0, List.copyOf(failed));
        }
        for (AuthorizationService.AccessTuple t : snapshot) {
            if (t.object() != null && t.object().startsWith("group:")
                    && "member".equals(t.relation()) && t.user() != null && t.user().startsWith("user:")) {
                UUID groupId = parseUuidAfterPrefix(t.object(), "group:");
                UUID userId = parseUuidAfterPrefix(t.user(), "user:");
                if (groupId == null || userId == null) {
                    continue;
                }
                if (sqlMembers.contains(groupId + "|" + userId)) {
                    continue;
                }
                try {
                    authorizationService.deleteLegacyGroupMemberUser(groupId, userId);
                    members++;
                } catch (RuntimeException e) {
                    errors++;
                    failed.add(t.object() + "#member@" + t.user());
                    log.warn("team-roles-v1: leftover member {} : {}", t.object(), e.getMessage());
                }
            }
        }
        for (AuthorizationService.AccessTuple t : snapshot) {
            String type = objectType(t.object());
            if (type == null || !RESOURCE_TYPES.contains(type)) {
                continue;
            }
            UUID groupId = parseGroupMemberSubject(t.user());
            if (groupId == null) {
                continue;
            }
            if (!ACL.contains(t.relation())
                    && !AuthorizationService.RELATION_DIRECT_ACCESS.equals(t.relation())) {
                continue;
            }
            try {
                authorizationService.rewriteLegacyGroupMemberGrantOn(
                        t.object(), type, t.relation(), groupId);
                rewritten++;
            } catch (RuntimeException e) {
                errors++;
                failed.add(t.object() + "#" + t.relation());
                log.warn("team-roles-v1: {} : {}", t.object(), e.getMessage());
            }
        }
        return new MigrationReport(false, members, rewritten, errors, 0, List.copyOf(failed));
    }

    private Leftover countLeftover() {
        int total = 0;
        List<String> samples = new ArrayList<>();
        try {
            for (AuthorizationService.AccessTuple t : authorizationService.readAllTuplesPaged()) {
                String type = objectType(t.object());
                if (type != null && RESOURCE_TYPES.contains(type)
                        && parseGroupMemberSubject(t.user()) != null) {
                    total++;
                    if (samples.size() < 20) {
                        samples.add(t.object() + "#" + t.relation() + "@" + t.user());
                    }
                } else if (type != null && "group".equals(type)
                        && "member".equals(t.relation())
                        && t.user() != null && t.user().startsWith("user:")) {
                    total++;
                    if (samples.size() < 20) {
                        samples.add(t.object() + "#member@" + t.user());
                    }
                }
            }
        } catch (RuntimeException e) {
            total++;
            samples.add("store:* (lecture leftover)");
        }
        return new Leftover(total, samples);
    }

    private static String objectType(String object) {
        if (object == null) {
            return null;
        }
        int colon = object.indexOf(':');
        return colon < 0 ? null : object.substring(0, colon);
    }

    static UUID parseGroupMemberSubject(String user) {
        if (user == null || !user.startsWith("group:") || !user.endsWith("#member")) {
            return null;
        }
        String raw = user.substring("group:".length(), user.length() - "#member".length());
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuidAfterPrefix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        try {
            return UUID.fromString(value.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private void publishModel(Resource resource, String label) {
        try {
            JsonNode root = MODEL_MAPPER.readTree(resource.getInputStream());
            WriteAuthorizationModelRequest writeReq = new WriteAuthorizationModelRequest()
                    .schemaVersion(root.get("schema_version").asText());
            List<TypeDefinition> defs = new ArrayList<>();
            for (JsonNode td : root.get("type_definitions")) {
                defs.add(MODEL_MAPPER.treeToValue(td, TypeDefinition.class));
            }
            writeReq.setTypeDefinitions(defs);
            String modelId = openFgaClient.writeAuthorizationModel(writeReq).get().getAuthorizationModelId();
            openFgaClient.setAuthorizationModelId(modelId);
            log.info("team-roles-v1 : modèle {} publié ({})", label, modelId);
        } catch (Exception e) {
            throw new TeamRolesMigrationFailedException(
                    "team-roles-v1 : publication du modèle " + label + " échouée : " + e.getMessage(),
                    e, 1, List.of("openfga-model"));
        }
    }

    private static void lock(Connection con) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_ID);
            ps.execute();
        }
    }

    private static void unlock(Connection con) {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_ID);
            ps.execute();
        } catch (Exception e) {
            log.warn("team-roles-v1 : pg_advisory_unlock : {}", e.toString());
        }
    }

    public record MigrationReport(
            boolean skipped,
            int membersRewritten,
            int grantsRewritten,
            int errors,
            int leftoverLegacy,
            List<String> failedObjects
    ) {
        static MigrationReport skippedReport() {
            return new MigrationReport(true, 0, 0, 0, 0, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skipped", skipped);
            m.put("migration", MIGRATION_NAME);
            m.put("membersRewritten", membersRewritten);
            m.put("grantsRewritten", grantsRewritten);
            m.put("errors", errors);
            m.put("leftoverLegacy", leftoverLegacy);
            m.put("failedObjects", failedObjects);
            return m;
        }
    }

    public static final class TeamRolesMigrationFailedException extends IllegalStateException {
        private final int errors;
        private final List<String> failedObjects;

        TeamRolesMigrationFailedException(String message, int errors, List<String> failedObjects) {
            super(message);
            this.errors = errors;
            this.failedObjects = failedObjects == null ? List.of() : List.copyOf(failedObjects);
        }

        TeamRolesMigrationFailedException(String message, Throwable cause, int errors, List<String> failedObjects) {
            super(message, cause);
            this.errors = errors;
            this.failedObjects = failedObjects == null ? List.of() : List.copyOf(failedObjects);
        }

        public int errors() {
            return errors;
        }

        public List<String> failedObjects() {
            return failedObjects;
        }
    }

    private record MemberRow(UUID groupId, UUID userId, String role) {}

    private record Leftover(int total, List<String> samples) {}
}
