// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformRoleService {

    private final PlatformRoleRepository platformRoleRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public PlatformRoleService(
            PlatformRoleRepository platformRoleRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.platformRoleRepository = platformRoleRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PlatformRoleView> list(UUID userId) {
        requireUser(userId);
        return platformRoleRepository.findByUserId(userId).stream()
                .map(PlatformRoleView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PlatformRoleView> listAll() {
        return platformRoleRepository.findAll().stream()
                .map(PlatformRoleView::from)
                .toList();
    }

    @Transactional
    public PlatformRoleView grant(UUID actorId, UUID userId, SocleRole role) {
        requireUser(userId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role requis");
        }
        var existing = platformRoleRepository.findByUserIdAndRole(userId, role.name());
        if (existing.isPresent()) {
            return PlatformRoleView.from(existing.get());
        }
        PlatformRoleEntity entity = new PlatformRoleEntity();
        entity.setUserId(userId);
        entity.setRole(role.name());
        entity.setGrantedBy(actorId);
        entity.setGrantedAt(Instant.now());
        PlatformRoleEntity saved = platformRoleRepository.save(entity);
        auditService.record(
                actorId, false,
                AuditActions.PLATFORM_ROLE_GRANTED,
                "user", userId,
                Map.of("role", role.name()),
                null
        );
        return PlatformRoleView.from(saved);
    }

    @Transactional
    public void revoke(UUID actorId, UUID userId, SocleRole role) {
        requireUser(userId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role requis");
        }
        var existing = platformRoleRepository.findByUserIdAndRole(userId, role.name());
        if (existing.isEmpty()) {
            return;
        }
        if (role == SocleRole.ADMINISTRATEUR_SYSTEME) {
            long admins = platformRoleRepository.countByRole(SocleRole.ADMINISTRATEUR_SYSTEME.name());
            if (admins <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Impossible de retirer le dernier ADMINISTRATEUR_SYSTEME");
            }
        }
        platformRoleRepository.deleteByUserIdAndRole(userId, role.name());
        auditService.record(
                actorId, false,
                AuditActions.PLATFORM_ROLE_REVOKED,
                "user", userId,
                Map.of("role", role.name()),
                null
        );
    }

    /** Bootstrap admin à la première connexion (INTERNAL / BOTH). */
    @Transactional
    public void grantBootstrapAdminIfNeeded(UUID userId, String subject) {
        if (platformRoleRepository.findByUserIdAndRole(userId, SocleRole.ADMINISTRATEUR_SYSTEME.name()).isPresent()) {
            return;
        }
        PlatformRoleEntity entity = new PlatformRoleEntity();
        entity.setUserId(userId);
        entity.setRole(SocleRole.ADMINISTRATEUR_SYSTEME.name());
        entity.setGrantedBy(null);
        entity.setGrantedAt(Instant.now());
        platformRoleRepository.save(entity);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("role", SocleRole.ADMINISTRATEUR_SYSTEME.name());
        meta.put("bootstrapSubject", subject);
        auditService.record(
                userId, false,
                AuditActions.PLATFORM_ROLE_GRANTED,
                "user", userId,
                meta,
                null
        );
    }

    private void requireUser(UUID userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "utilisateur introuvable");
        }
    }

    public record PlatformRoleView(
            UUID userId,
            String role,
            UUID grantedBy,
            Instant grantedAt
    ) {
        static PlatformRoleView from(PlatformRoleEntity e) {
            return new PlatformRoleView(e.getUserId(), e.getRole(), e.getGrantedBy(), e.getGrantedAt());
        }
    }
}
