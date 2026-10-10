// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.AccessDecisionCache;
import eu.socle.identity.PlatformRoleRepository;
import eu.socle.identity.SocleRole;
import eu.socle.pat.PatService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Activation / désactivation administrative d'un compte ({@code users.status}).
 * Un compte {@code disabled} est refusé par la politique d'accès même avec un JWT valide ;
 * ses jetons d'accès personnels sont révoqués dans la même transaction.
 */
@Service
public class UserAccountService {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final PlatformRoleRepository platformRoleRepository;
    private final AuditService auditService;
    private final AccessDecisionCache accessDecisionCache;
    private final PatService patService;

    public UserAccountService(
            UserRepository userRepository,
            UserIdentityRepository identityRepository,
            PlatformRoleRepository platformRoleRepository,
            AuditService auditService,
            AccessDecisionCache accessDecisionCache,
            PatService patService
    ) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.platformRoleRepository = platformRoleRepository;
        this.auditService = auditService;
        this.accessDecisionCache = accessDecisionCache;
        this.patService = patService;
    }

    @Transactional
    public UserEntity disable(UUID actorId, UUID userId) {
        UserEntity user = requireUser(userId);
        if (STATUS_DISABLED.equalsIgnoreCase(user.getStatus())) {
            return user;
        }
        guardLastSystemAdmin(userId);
        String previousStatus = user.getStatus() == null ? "" : user.getStatus();
        user.setStatus(STATUS_DISABLED);
        UserEntity saved = userRepository.save(user);
        invalidateCache(userId);
        patService.revokeAllForUser(userId, actorId, PatService.REASON_USER_DISABLED);
        auditService.record(
                actorId, false,
                AuditActions.USER_DISABLED,
                "user", userId,
                Map.of("previousStatus", previousStatus),
                null
        );
        return saved;
    }

    @Transactional
    public UserEntity enable(UUID actorId, UUID userId) {
        UserEntity user = requireUser(userId);
        if (STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            return user;
        }
        user.setStatus(STATUS_ACTIVE);
        UserEntity saved = userRepository.save(user);
        invalidateCache(userId);
        auditService.record(
                actorId, false,
                AuditActions.USER_ENABLED,
                "user", userId,
                Map.of(),
                null
        );
        return saved;
    }

    private void guardLastSystemAdmin(UUID userId) {
        String role = SocleRole.ADMINISTRATEUR_SYSTEME.name();
        if (platformRoleRepository.findByUserIdAndRole(userId, role).isEmpty()) {
            return;
        }
        // Dernier titulaire du rôle, ou dernier titulaire dont le compte n'est pas déjà désactivé.
        if (platformRoleRepository.countByRole(role) <= 1
                || platformRoleRepository.countEnabledByRole(role) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Impossible de désactiver le dernier ADMINISTRATEUR_SYSTEME");
        }
    }

    /**
     * Invalide le cache de décisions pour chaque identité du compte. Après commit pour qu'une
     * requête concurrente ne remette pas l'ancien statut en cache.
     */
    private void invalidateCache(UUID userId) {
        Runnable invalidate = () -> identityRepository.findByUserId(userId)
                .forEach(i -> accessDecisionCache.invalidate(i.getIssuer(), i.getSubject()));
        // Immédiat (visible dans la transaction) et rejoué après commit.
        invalidate.run();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        }
    }

    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    }
}
