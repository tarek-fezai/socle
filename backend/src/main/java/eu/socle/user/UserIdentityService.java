// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.user;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rattachement contrôlé d'identités OIDC à un compte Socle — jamais automatique
 * sauf option explicite {@code link-by-verified-email}.
 */
@Service
public class UserIdentityService {

    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public UserIdentityService(
            UserIdentityRepository identityRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findUserByIdentity(String issuer, String subject) {
        return identityRepository.findByIssuerAndSubject(issuer, subject)
                .flatMap(i -> userRepository.findById(i.getUserId()));
    }

    @Transactional(readOnly = true)
    public List<IdentityView> listForUser(UUID userId) {
        requireUser(userId);
        return identityRepository.findByUserId(userId).stream().map(IdentityView::from).toList();
    }

    @Transactional
    public IdentityView link(UUID actorId, UUID userId, String issuer, String subject) {
        requireUser(userId);
        String iss = requireNonBlank(issuer, "issuer");
        String sub = requireNonBlank(subject, "subject");

        Optional<UserIdentityEntity> existing = identityRepository.findByIssuerAndSubject(iss, sub);
        if (existing.isPresent()) {
            UserIdentityEntity row = existing.get();
            if (!row.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Identité déjà liée à un autre compte");
            }
            return IdentityView.from(row);
        }

        UserIdentityEntity entity = new UserIdentityEntity();
        entity.setUserId(userId);
        entity.setIssuer(iss);
        entity.setSubject(sub);
        entity.setLinkedAt(Instant.now());
        entity.setLinkedBy(actorId);
        UserIdentityEntity saved = identityRepository.save(entity);

        auditService.record(
                actorId, false,
                AuditActions.USER_IDENTITY_LINKED,
                "user", userId,
                Map.of("issuer", iss, "subject", sub),
                null
        );
        return IdentityView.from(saved);
    }

    /** Crée l'identité primaire à la première inscription (sans audit admin). */
    @Transactional
    public void ensurePrimaryIdentity(UUID userId, String issuer, String subject) {
        if (identityRepository.findByIssuerAndSubject(issuer, subject).isPresent()) {
            return;
        }
        UserIdentityEntity entity = new UserIdentityEntity();
        entity.setUserId(userId);
        entity.setIssuer(issuer);
        entity.setSubject(subject);
        entity.setLinkedAt(Instant.now());
        entity.setLinkedBy(null);
        identityRepository.save(entity);
    }

    @Transactional
    public void unlink(UUID actorId, UUID userId, String issuer, String subject) {
        requireUser(userId);
        String iss = requireNonBlank(issuer, "issuer");
        String sub = requireNonBlank(subject, "subject");

        UserIdentityEntity row = identityRepository.findByUserIdAndIssuerAndSubject(userId, iss, sub)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Identité introuvable"));

        if (identityRepository.countByUserId(userId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible de retirer la dernière identité d'un compte");
        }

        identityRepository.delete(row);
        auditService.record(
                actorId, false,
                AuditActions.USER_IDENTITY_UNLINKED,
                "user", userId,
                Map.of("issuer", iss, "subject", sub),
                null
        );
    }

    /**
     * Import CSV : colonnes {@code user_id|old_subject,new_issuer,new_subject}.
     * {@code dryRun=true} (défaut) n'écrit rien.
     */
    @Transactional
    public ImportReport importIdentities(UUID actorId, String csv, boolean dryRun) {
        List<ImportLineResult> applied = new ArrayList<>();
        List<ImportLineResult> conflicts = new ArrayList<>();
        List<ImportLineResult> unknown = new ArrayList<>();
        List<ImportLineResult> skipped = new ArrayList<>();

        if (csv == null || csv.isBlank()) {
            return new ImportReport(dryRun, List.of(), List.of(), List.of(), List.of());
        }

        String[] lines = csv.split("\\R");
        int lineNo = 0;
        for (String rawLine : lines) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (lineNo == 1 && looksLikeHeader(line)) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 3) {
                unknown.add(new ImportLineResult(lineNo, line, "format invalide (3 colonnes attendues)"));
                continue;
            }
            String key = parts[0].trim();
            String newIssuer = parts[1].trim();
            String newSubject = parts[2].trim();
            if (key.isEmpty() || newIssuer.isEmpty() || newSubject.isEmpty()) {
                unknown.add(new ImportLineResult(lineNo, line, "champs vides"));
                continue;
            }

            Optional<UUID> userIdOpt = resolveUserKey(key);
            if (userIdOpt.isEmpty()) {
                unknown.add(new ImportLineResult(lineNo, line, "utilisateur inconnu: " + key));
                continue;
            }
            UUID userId = userIdOpt.get();

            Optional<UserIdentityEntity> occupied = identityRepository.findByIssuerAndSubject(newIssuer, newSubject);
            if (occupied.isPresent()) {
                if (occupied.get().getUserId().equals(userId)) {
                    skipped.add(new ImportLineResult(lineNo, line, "déjà lié (idempotent)"));
                } else {
                    conflicts.add(new ImportLineResult(lineNo, line,
                            "identité déjà liée à " + occupied.get().getUserId()));
                }
                continue;
            }

            if (!dryRun) {
                link(actorId, userId, newIssuer, newSubject);
            }
            applied.add(new ImportLineResult(lineNo, line, dryRun ? "serait appliqué" : "lié"));
        }

        if (!dryRun && !applied.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("applied", applied.size());
            meta.put("conflicts", conflicts.size());
            meta.put("unknown", unknown.size());
            meta.put("skipped", skipped.size());
            auditService.record(
                    actorId, false,
                    AuditActions.USER_IDENTITIES_IMPORTED,
                    "user", null,
                    meta,
                    null
            );
        }

        return new ImportReport(dryRun, applied, conflicts, unknown, skipped);
    }

    private Optional<UUID> resolveUserKey(String key) {
        try {
            UUID id = UUID.fromString(key);
            if (userRepository.existsById(id)) {
                return Optional.of(id);
            }
        } catch (IllegalArgumentException ignored) {
            // old_subject
        }
        List<UserIdentityEntity> bySubject = identityRepository.findAllBySubject(key);
        if (bySubject.size() == 1) {
            return Optional.of(bySubject.getFirst().getUserId());
        }
        return Optional.empty();
    }

    private static boolean looksLikeHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("user_id") || lower.contains("old_subject") || lower.contains("new_issuer");
    }

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " requis");
        }
        return value.trim();
    }

    public record IdentityView(UUID userId, String issuer, String subject, Instant linkedAt, UUID linkedBy) {
        static IdentityView from(UserIdentityEntity e) {
            return new IdentityView(e.getUserId(), e.getIssuer(), e.getSubject(), e.getLinkedAt(), e.getLinkedBy());
        }
    }

    public record ImportLineResult(int line, String raw, String detail) {}

    public record ImportReport(
            boolean dryRun,
            List<ImportLineResult> applied,
            List<ImportLineResult> conflicts,
            List<ImportLineResult> unknown,
            List<ImportLineResult> skipped
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dryRun", dryRun);
            m.put("applied", applied);
            m.put("conflicts", conflicts);
            m.put("unknown", unknown);
            m.put("skipped", skipped);
            return m;
        }
    }
}
