// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.licence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.licence.LicenceDtos.ImportLicenceRequest;
import eu.socle.licence.LicenceDtos.LicenceView;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Licence d'instance : vérification Ed25519 hors ligne, limite de sièges à la création
 * d'utilisateurs (jamais de blocage des comptes existants).
 *
 * <p>Sans licence : mode évaluation {@link #EVALUATION_MAX_USERS} utilisateurs actifs.
 */
@Service
public class LicenceService {

    private static final Logger log = LoggerFactory.getLogger(LicenceService.class);

    /** Limite d'évaluation sans fichier de licence (utilisateurs {@code status = 'active'}). */
    public static final int EVALUATION_MAX_USERS = 5;

    public static final String STATUS_VALIDE = "valide";
    public static final String STATUS_EXPIRE_BIENTOT = "expire_bientot";
    public static final String STATUS_EXPIREE = "expiree";
    public static final String STATUS_ABSENTE = "absente";

    private static final Duration EXPIRING_SOON = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PublicKey publicKey;

    @org.springframework.beans.factory.annotation.Autowired
    public LicenceService(
            JdbcTemplate jdbc,
            IdentityFacade identityFacade,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("classpath:licence/ed25519-public.b64") Resource publicKeyResource
    ) {
        this.jdbc = jdbc;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        try {
            String b64 = publicKeyResource.getContentAsString(StandardCharsets.UTF_8);
            this.publicKey = LicenceCrypto.publicKeyFromRawBase64(b64);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de charger la clé publique de licence", e);
        }
        log.info("Licence : clé publique Ed25519 chargée (vérification hors ligne)");
    }

    /** Constructeur tests (clé fournie). */
    LicenceService(
            JdbcTemplate jdbc,
            IdentityFacade identityFacade,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock,
            PublicKey publicKey
    ) {
        this.jdbc = jdbc;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.publicKey = publicKey;
    }

    @Transactional(readOnly = true)
    public LicenceView get(Jwt jwt) {
        requireAdmin(jwt);
        return view();
    }

    /** Vue interne (bandeau admin) — admin uniquement. */
    @Transactional(readOnly = true)
    public LicenceView adminBanner(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return view();
    }

    @Transactional
    public LicenceView importLicence(Jwt jwt, ImportLicenceRequest request) {
        UserEntity admin = requireAdmin(jwt);
        if (request == null || request.licenceJson() == null || request.licenceJson().isBlank()) {
            reject(admin.getId(), "corps vide");
            throw ApiErrors.licenceRejected("Fichier de licence vide");
        }
        Parsed parsed;
        try {
            parsed = parseAndVerify(request.licenceJson());
        } catch (CodedReject e) {
            reject(admin.getId(), e.reason);
            throw ApiErrors.licenceRejected(e.reason);
        }

        jdbc.update("DELETE FROM instance_licence");
        jdbc.update("""
                INSERT INTO instance_licence (
                    id, license_id, licensee, edition, issued_at, expires_at, max_users,
                    payload_json, imported_at, imported_by)
                VALUES (true, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?)
                """,
                parsed.licenseId(), parsed.licensee(), parsed.edition(),
                Timestamp.from(parsed.issuedAt()), Timestamp.from(parsed.expiresAt()),
                parsed.maxUsers(), request.licenceJson().trim(), admin.getId());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("licenseId", parsed.licenseId());
        meta.put("licensee", parsed.licensee());
        meta.put("edition", parsed.edition());
        meta.put("expiresAt", parsed.expiresAt().toString());
        meta.put("maxUsers", parsed.maxUsers());
        auditService.record(admin.getId(), false, AuditActions.LICENCE_IMPORTED,
                "instance_licence", null, meta, null);
        return view();
    }

    /**
     * Refuse la création d'un nouvel utilisateur si la limite de sièges est atteinte
     * (licence absente / invalide / expirée / maxUsers). Les comptes existants ne sont jamais bloqués.
     */
    @Transactional(readOnly = true)
    public void assertCanCreateUser() {
        SeatPolicy policy = seatPolicy();
        long active = countActiveUsers();
        if (active >= policy.maxUsers()) {
            throw ApiErrors.licenceUserLimit(policy.maxUsers(), active, policy.reason());
        }
    }

    @Transactional(readOnly = true)
    public LicenceView view() {
        long active = countActiveUsers();
        Stored stored = loadStored();
        Instant now = clock.instant();
        if (stored == null) {
            return new LicenceView(
                    STATUS_ABSENTE, null, null, null, null, null, null,
                    active, EVALUATION_MAX_USERS, true,
                    "Mode évaluation : " + EVALUATION_MAX_USERS
                            + " utilisateurs actifs maximum. Importez une licence.");
        }
        String status;
        String banner = null;
        if (!now.isBefore(stored.expiresAt())) {
            status = STATUS_EXPIREE;
            banner = "Licence expirée le " + stored.expiresAt()
                    + ". Création de nouveaux utilisateurs refusée (limite d'évaluation "
                    + EVALUATION_MAX_USERS + ").";
        } else if (!now.isBefore(stored.expiresAt().minus(EXPIRING_SOON))) {
            status = STATUS_EXPIRE_BIENTOT;
            banner = "Licence expire bientôt (" + stored.expiresAt() + ").";
        } else {
            status = STATUS_VALIDE;
        }
        if (active >= stored.maxUsers() && STATUS_VALIDE.equals(status)) {
            banner = "Limite de sièges atteinte (" + active + "/" + stored.maxUsers()
                    + "). Création de nouveaux utilisateurs refusée.";
        }
        return new LicenceView(
                status, stored.licenseId(), stored.licensee(), stored.edition(),
                stored.issuedAt(), stored.expiresAt(), stored.maxUsers(),
                active, seatPolicy().maxUsers(), false, banner);
    }

    private SeatPolicy seatPolicy() {
        Stored stored = loadStored();
        Instant now = clock.instant();
        if (stored == null) {
            return new SeatPolicy(EVALUATION_MAX_USERS, "aucune licence (évaluation)");
        }
        if (!now.isBefore(stored.expiresAt())) {
            return new SeatPolicy(EVALUATION_MAX_USERS, "licence expirée");
        }
        return new SeatPolicy(stored.maxUsers(), "licence");
    }

    private long countActiveUsers() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status = 'active' AND COALESCE(is_system_account, false) = false",
                Long.class);
        return n == null ? 0 : n;
    }

    private Stored loadStored() {
        List<Stored> rows = jdbc.query("""
                SELECT license_id, licensee, edition, issued_at, expires_at, max_users, payload_json::text
                  FROM instance_licence WHERE id = true
                """,
                (rs, i) -> new Stored(
                        rs.getString("license_id"),
                        rs.getString("licensee"),
                        rs.getString("edition"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("max_users"),
                        rs.getString(7)));
        if (rows.isEmpty()) {
            return null;
        }
        Stored s = rows.getFirst();
        // Re-vérifie la signature au chargement (démarrage / lecture).
        try {
            parseAndVerify(s.payloadJson());
            return s;
        } catch (CodedReject e) {
            log.warn("Licence stockée rejetée à la relecture : {}", e.reason);
            return null;
        }
    }

    Parsed parseAndVerify(String json) throws CodedReject {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new CodedReject("JSON invalide");
        }
        if (root == null || !root.isObject()) {
            throw new CodedReject("JSON invalide");
        }
        try {
            LicenceCrypto.canonicalPayload(root);
        } catch (IllegalArgumentException e) {
            throw new CodedReject(e.getMessage());
        }
        if (!LicenceCrypto.verify(root, publicKey)) {
            throw new CodedReject("signature invalide ou altérée");
        }
        try {
            Instant issued = Instant.parse(root.get("issuedAt").asText());
            Instant expires = Instant.parse(root.get("expiresAt").asText());
            if (!expires.isAfter(issued)) {
                throw new CodedReject("expiresAt doit être postérieur à issuedAt");
            }
            return new Parsed(
                    root.get("licenseId").asText(),
                    root.get("licensee").asText(),
                    root.get("edition").asText(),
                    issued,
                    expires,
                    root.get("maxUsers").asInt());
        } catch (CodedReject e) {
            throw e;
        } catch (Exception e) {
            throw new CodedReject("dates invalides");
        }
    }

    private void reject(UUID actorId, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", reason);
        auditService.record(actorId, false, AuditActions.LICENCE_REJECTED,
                "instance_licence", null, meta, null);
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }

    private record Stored(
            String licenseId, String licensee, String edition,
            Instant issuedAt, Instant expiresAt, int maxUsers, String payloadJson) {}

    private record Parsed(
            String licenseId, String licensee, String edition,
            Instant issuedAt, Instant expiresAt, int maxUsers) {}

    private record SeatPolicy(int maxUsers, String reason) {}

    private static final class CodedReject extends Exception {
        final String reason;
        CodedReject(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
