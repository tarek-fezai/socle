// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import eu.socle.identity.AccessAuditService;
import eu.socle.identity.AccessDeniedReason;
import eu.socle.identity.AccessPolicyDeniedException;
import eu.socle.identity.AccessPolicyService;
import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.identity.IdentityProperties;
import eu.socle.identity.PlatformRoleService;
import eu.socle.licence.LicenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserSyncService {

    private static final Logger log = LoggerFactory.getLogger(UserSyncService.class);

    private final UserRepository userRepository;
    private final UserIdentityService identityService;
    private final UserIdentityRepository identityRepository;
    private final IdentityClaimsMapper claimsMapper;
    private final IdentityProperties identityProperties;
    private final PlatformRoleService platformRoleService;
    private final AccessPolicyService accessPolicyService;
    private final AccessAuditService accessAuditService;
    private final LicenceService licenceService;

    public UserSyncService(
            UserRepository userRepository,
            UserIdentityService identityService,
            UserIdentityRepository identityRepository,
            IdentityClaimsMapper claimsMapper,
            IdentityProperties identityProperties,
            PlatformRoleService platformRoleService,
            AccessPolicyService accessPolicyService,
            AccessAuditService accessAuditService,
            @Lazy @Autowired(required = false) LicenceService licenceService
    ) {
        this.accessPolicyService = accessPolicyService;
        this.accessAuditService = accessAuditService;
        this.userRepository = userRepository;
        this.identityService = identityService;
        this.identityRepository = identityRepository;
        this.claimsMapper = claimsMapper;
        this.identityProperties = identityProperties;
        this.platformRoleService = platformRoleService;
        this.licenceService = licenceService;
    }

    @Transactional
    public UserEntity syncFromJwt(Jwt jwt) {
        String subject = claimsMapper.subject(jwt);
        String issuer = claimsMapper.issuer(jwt);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "subject claim manquant");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "issuer manquant");
        }

        String emailRaw = claimsMapper.email(jwt);
        String nameRaw = claimsMapper.name(jwt);
        boolean emailVerified = claimsMapper.emailVerified(jwt);

        Optional<UserEntity> byIdentity = identityService.findUserByIdentity(issuer, subject);
        boolean firstLogin = byIdentity.isEmpty();

        UserEntity user;
        boolean newAccount = false;
        if (byIdentity.isPresent()) {
            user = byIdentity.get();
            if (AccessPolicyService.isDisabled(user)) {
                throw new AccessPolicyDeniedException(AccessDeniedReason.ACCOUNT_DISABLED);
            }
        } else {
            Optional<UserEntity> linked = maybeLinkByVerifiedEmail(issuer, emailRaw, emailVerified);
            if (linked.isPresent()) {
                user = linked.get();
                firstLogin = false;
                // Compte existant rattaché à cette identité : désactivé / domaine / groupe.
                requireGranted(accessPolicyService.evaluate(jwt, user));
            } else {
                Optional<UserEntity> orphan = findExistingUserBySubject(subject);
                if (orphan.isPresent()) {
                    // Ligne users déjà présente (ex. id = sub UUID) sans identité : pas une création.
                    user = orphan.get();
                    firstLogin = false;
                    if (AccessPolicyService.isDisabled(user)) {
                        throw new AccessPolicyDeniedException(AccessDeniedReason.ACCOUNT_DISABLED);
                    }
                    requireGranted(accessPolicyService.evaluate(jwt, user));
                } else {
                    // Création effective uniquement — limite de sièges / licence ici seulement.
                    requireGranted(accessPolicyService.evaluate(jwt, null));
                    if (licenceService != null) {
                        licenceService.assertCanCreateUser(subject);
                    }
                    newAccount = true;
                    user = new UserEntity();
                    user.setId(allocateId(subject));
                    user.setSystemAccount(false);
                    user.setStatus("active");
                }
            }
        }

        String email = firstNonBlank(emailRaw, subject + "@users.local");
        String name = firstNonBlank(nameRaw, email);
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setDisplayName(name);
        user.setAvatarInitials(initials(name));
        // Première connexion réussie : nouveau compte, ou compte provisionné jamais connecté.
        boolean firstGrant = newAccount || user.getLastLoginAt() == null;
        user.setLastLoginAt(Instant.now());

        UserEntity saved = userRepository.save(user);

        if (identityRepository.findByIssuerAndSubject(issuer, subject).isEmpty()) {
            identityService.ensurePrimaryIdentity(saved.getId(), issuer, subject);
        }

        if (firstLogin) {
            maybeBootstrapAdmin(saved, subject);
        }
        if (firstGrant) {
            auditAccessGrantedAfterCommit(saved.getId(), issuer, subject);
        }
        return saved;
    }

    private static void requireGranted(AccessPolicyService.Decision decision) {
        if (!decision.granted()) {
            throw new AccessPolicyDeniedException(decision.reason());
        }
    }

    /**
     * Audit {@code auth.access_granted} (actor_id → users) : après commit pour que la FK soit
     * satisfaite par l'écriture asynchrone.
     */
    private void auditAccessGrantedAfterCommit(UUID userId, String issuer, String subject) {
        IdentityProperties.AccessMode mode = identityProperties.getAccessPolicy().getMode();
        Runnable audit = () -> accessAuditService.recordGranted(userId, issuer, subject, mode);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    audit.run();
                }
            });
        } else {
            audit.run();
        }
    }

    /**
     * Option dangereuse {@code link-by-verified-email} : rattache uniquement si
     * email_verified, un seul compte pour cet e-mail, et aucune identité pour cet issuer.
     */
    private Optional<UserEntity> maybeLinkByVerifiedEmail(String issuer, String emailRaw, boolean emailVerified) {
        if (!identityProperties.isLinkByVerifiedEmail()) {
            return Optional.empty();
        }
        if (!emailVerified || emailRaw == null || emailRaw.isBlank()) {
            return Optional.empty();
        }
        String email = emailRaw.toLowerCase(Locale.ROOT).trim();
        List<UserEntity> matches = userRepository.findAllByEmailIgnoreCase(email);
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                log.warn("link-by-verified-email: {} comptes pour {} — aucun rattachement",
                        matches.size(), email);
            }
            return Optional.empty();
        }
        UserEntity candidate = matches.getFirst();
        if (identityRepository.existsByUserIdAndIssuer(candidate.getId(), issuer)) {
            return Optional.empty();
        }
        log.info("link-by-verified-email: rattachement {} → user {}", issuer, candidate.getId());
        return Optional.of(candidate);
    }

    private void maybeBootstrapAdmin(UserEntity user, String subject) {
        var source = identityProperties.getRoleSource();
        if (source != IdentityProperties.RoleSource.INTERNAL
                && source != IdentityProperties.RoleSource.BOTH) {
            return;
        }
        if (!identityProperties.isBootstrapAdminSubject(subject)) {
            return;
        }
        platformRoleService.grantBootstrapAdminIfNeeded(user.getId(), subject);
    }

    /**
     * Compte déjà en base dont l'id égale le {@code sub} UUID — sans passer par la création
     * (donc sans contrôle licence). Cas typique : bootstrap / seed / sync partiel.
     */
    private Optional<UserEntity> findExistingUserBySubject(String subject) {
        try {
            UUID asUuid = UUID.fromString(subject);
            return userRepository.findById(asUuid);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private UUID allocateId(String subject) {
        try {
            UUID asUuid = UUID.fromString(subject);
            if (!userRepository.existsById(asUuid)) {
                return asUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // subject non-UUID
        }
        return UUID.randomUUID();
    }

    private static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0) {
            return "?";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
