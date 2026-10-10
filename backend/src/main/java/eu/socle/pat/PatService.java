// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.web.ApiErrors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jetons d'accès personnels : création (secret affiché une seule fois), liste, révocation,
 * authentification. Le jeton en clair n'est ni stocké, ni journalisé, ni audité.
 */
@Service
public class PatService {

    public static final int MIN_EXPIRY_DAYS = 1;
    public static final int MAX_EXPIRY_DAYS = 90;
    public static final int MAX_ACTIVE_TOKENS = 10;
    public static final int NAME_MAX_LENGTH = 100;
    public static final String REASON_MANUAL = "manual";
    public static final String REASON_USER_DISABLED = "user_disabled";
    static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(1);
    static final Duration EXPIRY_NOTICE = Duration.ofDays(7);
    private static final int LOOKUP_ATTEMPTS = 3;

    public enum Status { active, expired, revoked }

    /** Résultat de création : {@code token} n'est renvoyé qu'ici. */
    public record Created(PatRepository.PatRow row, String token) {
        @Override
        public String toString() {
            return "PatService.Created[id=" + row.id() + "]";
        }
    }

    /** Jeton présenté valide : compte actif, identité liée. */
    public record Authenticated(
            UUID patId, UUID userId, PatScope scope, Instant expiresAt,
            String issuer, String subject, String email, String displayName
    ) {}

    private final PatRepository repository;
    private final PatHasher hasher;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Instant> lastTouched = new ConcurrentHashMap<>();

    public PatService(PatRepository repository, PatHasher hasher, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<PatRepository.PatRow> list(UUID userId) {
        return repository.findByUser(userId);
    }

    public Status status(PatRepository.PatRow row) {
        if (row.revokedAt() != null) {
            return Status.revoked;
        }
        return row.expiresAt().isAfter(clock.instant()) ? Status.active : Status.expired;
    }

    public static void validateExpiryDays(Integer days) {
        if (days == null || days < MIN_EXPIRY_DAYS || days > MAX_EXPIRY_DAYS) {
            throw ApiErrors.patExpiryInvalid(MIN_EXPIRY_DAYS, MAX_EXPIRY_DAYS);
        }
    }

    @Transactional
    public Created create(UUID userId, String rawName, PatScope scope, Integer expiresInDays) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nom obligatoire (" + NAME_MAX_LENGTH + " caractères maximum)");
        }
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Portée obligatoire : read ou read_write");
        }
        validateExpiryDays(expiresInDays);

        repository.lockUser(userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (repository.countActive(userId, now) >= MAX_ACTIVE_TOKENS) {
            throw ApiErrors.patLimitReached(MAX_ACTIVE_TOKENS);
        }
        Instant expiresAt = now.plus(Duration.ofDays(expiresInDays));
        UUID id = UUID.randomUUID();
        PatTokenFormat.Generated generated = insertWithFreshLookup(id, userId, name, scope, now, expiresAt);

        PatRepository.PatRow row = new PatRepository.PatRow(
                id, userId, name, generated.last4(), scope, now, expiresAt, null, null, null);
        auditService.recordSync(userId, false, AuditActions.PAT_CREATED, "personal_access_token", id,
                auditMetadata(row, null), null);
        return new Created(row, generated.token());
    }

    private PatTokenFormat.Generated insertWithFreshLookup(
            UUID id, UUID userId, String name, PatScope scope, Instant now, Instant expiresAt) {
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < LOOKUP_ATTEMPTS; attempt++) {
            PatTokenFormat.Generated generated = PatTokenFormat.generate(random);
            try {
                repository.insert(id, userId, name, generated.lookup(), hasher.hash(generated.secret()),
                        generated.last4(), scope, now, expiresAt);
                return generated;
            } catch (DuplicateKeyException e) {
                last = e;
            }
        }
        throw new IllegalStateException("collision de lookup PAT répétée", last);
    }

    /** {@code false} si le jeton n'appartient pas à l'utilisateur (404 côté API). */
    @Transactional
    public boolean revoke(UUID userId, UUID tokenId) {
        Optional<PatRepository.PatRow> row = repository.findByIdAndUser(tokenId, userId);
        if (row.isEmpty()) {
            return false;
        }
        if (repository.revoke(tokenId, clock.instant(), REASON_MANUAL)) {
            auditService.recordSync(userId, false, AuditActions.PAT_REVOKED, "personal_access_token", tokenId,
                    auditMetadata(row.get(), REASON_MANUAL), null);
        }
        lastTouched.remove(tokenId);
        return true;
    }

    /** Désactivation du compte : appelé dans la transaction de désactivation. */
    @Transactional
    public int revokeAllForUser(UUID userId, UUID actorId, String reason) {
        List<PatRepository.PatRow> revoked = repository.revokeAllForUser(userId, clock.instant(), reason);
        for (PatRepository.PatRow row : revoked) {
            auditService.recordSync(actorId, false, AuditActions.PAT_REVOKED, "personal_access_token", row.id(),
                    auditMetadata(row, reason), null);
            lastTouched.remove(row.id());
        }
        return revoked.size();
    }

    /** Vide si inconnu, hash différent, expiré, révoqué, compte inactif ou sans identité — sans distinction. */
    public Optional<Authenticated> authenticate(String presented) {
        Optional<PatTokenFormat.Parsed> parsed = PatTokenFormat.parse(presented);
        if (parsed.isEmpty()) {
            hasher.matches("", null);
            return Optional.empty();
        }
        Optional<PatRepository.AuthRow> found = repository.findForAuthentication(parsed.get().lookup());
        boolean hashOk = hasher.matches(parsed.get().secret(), found.map(PatRepository.AuthRow::tokenHash).orElse(null));
        if (!hashOk) {
            return Optional.empty();
        }
        PatRepository.AuthRow row = found.get();
        Instant now = clock.instant();
        if (row.revokedAt() != null || !row.expiresAt().isAfter(now)
                || !"active".equalsIgnoreCase(row.userStatus())) {
            return Optional.empty();
        }
        Optional<PatRepository.Identity> identity = repository.firstIdentity(row.userId());
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        touchLastUsed(row, now);
        return Optional.of(new Authenticated(row.id(), row.userId(), row.scope(), row.expiresAt(),
                identity.get().issuer(), identity.get().subject(), row.email(), row.displayName()));
    }

    private void touchLastUsed(PatRepository.AuthRow row, Instant now) {
        if (row.lastUsedAt() != null && row.lastUsedAt().isAfter(now.minus(LAST_USED_THROTTLE))) {
            return;
        }
        Instant previous = lastTouched.get(row.id());
        if (previous != null && previous.isAfter(now.minus(LAST_USED_THROTTLE))) {
            return;
        }
        lastTouched.put(row.id(), now);
        repository.touchLastUsed(row.id(), now);
    }

    /** Notification in-app J-7 (une par jeton, idempotent). */
    public int notifyExpiringTokens() {
        Instant now = clock.instant();
        return repository.insertExpiryNotifications(now, now.plus(EXPIRY_NOTICE));
    }

    private static Map<String, Object> auditMetadata(PatRepository.PatRow row, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (reason != null) {
            meta.put("reason", reason);
        }
        meta.put("name", row.name());
        meta.put("last4", row.last4());
        meta.put("scope", row.scope().value());
        meta.put("expiresAt", row.expiresAt().toString());
        return meta;
    }
}
