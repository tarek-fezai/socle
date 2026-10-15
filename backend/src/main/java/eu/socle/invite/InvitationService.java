// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.identity.IdentityFacade;
import eu.socle.identity.IdentityProperties;
import eu.socle.identity.PatClaims;
import eu.socle.identity.PlatformRoleService;
import eu.socle.identity.SocleRole;
import eu.socle.licence.LicenceService;
import eu.socle.team.GroupService;
import eu.socle.team.TeamRole;
import eu.socle.user.UserEntity;
import eu.socle.user.UserIdentityService;
import eu.socle.user.UserRepository;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InvitationRepository repository;
    private final InviteHasher hasher;
    private final InviteProperties inviteProperties;
    private final IdentityProperties identityProperties;
    private final IdentityClaimsMapper claimsMapper;
    private final IdentityFacade identityFacade;
    private final UserRepository userRepository;
    private final UserIdentityService identityService;
    private final PlatformRoleService platformRoleService;
    private final GroupService groupService;
    private final LicenceService licenceService;
    private final InvitationMailer mailer;
    private final AuditService auditService;
    private final Clock clock;
    private final InvitationCapability capability;

    public InvitationService(
            InvitationRepository repository,
            InviteHasher hasher,
            InviteProperties inviteProperties,
            IdentityProperties identityProperties,
            IdentityClaimsMapper claimsMapper,
            IdentityFacade identityFacade,
            UserRepository userRepository,
            UserIdentityService identityService,
            PlatformRoleService platformRoleService,
            GroupService groupService,
            LicenceService licenceService,
            InvitationMailer mailer,
            AuditService auditService,
            Clock clock,
            InvitationCapability capability
    ) {
        this.repository = repository;
        this.hasher = hasher;
        this.inviteProperties = inviteProperties;
        this.identityProperties = identityProperties;
        this.claimsMapper = claimsMapper;
        this.identityFacade = identityFacade;
        this.userRepository = userRepository;
        this.identityService = identityService;
        this.platformRoleService = platformRoleService;
        this.groupService = groupService;
        this.licenceService = licenceService;
        this.mailer = mailer;
        this.auditService = auditService;
        this.clock = clock;
        this.capability = capability;
    }

    public boolean hasPendingForVerifiedEmail(String email, boolean emailVerified) {
        if (!emailVerified || email == null || email.isBlank()) {
            return false;
        }
        return repository.hasPendingForEmail(normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public List<InvitationDtos.InvitationView> list(Jwt jwt) {
        requireAdmin(jwt);
        return repository.listPending().stream().map(this::toView).toList();
    }

    @Transactional
    public InvitationDtos.InvitationView create(Jwt jwt, InvitationDtos.CreateRequest request) {
        capability.requireEnabled();
        UserEntity admin = requireAdmin(jwt);
        String email = normalizeEmail(request.email());
        if (!userRepository.findAllByEmailIgnoreCase(email).isEmpty()) {
            throw ApiErrors.invitationEmailExists();
        }
        if (repository.findPendingByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une invitation est déjà en attente pour cet e-mail");
        }
        SocleRole global = parseGlobalRole(request.globalRole());
        if (global == SocleRole.ADMINISTRATEUR_SYSTEME && !Boolean.TRUE.equals(request.confirmAdmin())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Confirmation explicite requise pour inviter un administrateur");
        }
        UUID teamId = request.teamId();
        String teamRole = null;
        if (teamId != null) {
            groupService.requireExists(teamId);
            TeamRole role = request.teamRole() == null || request.teamRole().isBlank()
                    ? TeamRole.contributeur
                    : TeamRole.parse(request.teamRole());
            teamRole = role.name();
        } else if (request.teamRole() != null && !request.teamRole().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "teamRole sans teamId");
        }
        Instant now = clock.instant();
        String token = newToken();
        UUID id = UUID.randomUUID();
        InvitationRow row = new InvitationRow(
                id, email, teamId, teamRole, global.name(), hasher.hash(token), "pending",
                now.plus(Duration.ofDays(inviteProperties.ttlDays())),
                admin.getId(), null, null, now, now, 1);
        repository.insert(row);
        mailer.sendInvite(email, mailer.acceptUrl(token), null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("email", email);
        if (teamId != null) {
            meta.put("teamId", teamId.toString());
            meta.put("teamRole", teamRole);
        }
        meta.put("globalRole", global.name());
        auditService.record(admin.getId(), false, AuditActions.INVITATION_SENT, "invitation", id, meta, null);
        if (global == SocleRole.ADMINISTRATEUR_SYSTEME) {
            auditService.recordSync(
                    admin.getId(), false, AuditActions.INVITATION_ADMIN_ROLE_INVITED,
                    "invitation", id, Map.of("email", email, "globalRole", global.name()), null);
        }
        return toView(repository.findById(id).orElse(row));
    }

    @Transactional
    public InvitationDtos.InvitationView resend(Jwt jwt, UUID id) {
        capability.requireEnabled();
        UserEntity admin = requireAdmin(jwt);
        InvitationRow row = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation introuvable"));
        if (!row.pending() || row.expiredClock(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation non renvoyable");
        }
        Instant now = clock.instant();
        int min = Math.max(0, inviteProperties.resendMinIntervalSeconds());
        if (min > 0 && row.lastSentAt() != null
                && Duration.between(row.lastSentAt(), now).getSeconds() < min) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Renvoi trop fréquent");
        }
        String token = newToken();
        repository.replaceToken(id, hasher.hash(token), now);
        mailer.sendInvite(row.email(), mailer.acceptUrl(token), null);
        auditService.record(admin.getId(), false, AuditActions.INVITATION_RESENT, "invitation", id, Map.of(), null);
        return toView(repository.findById(id).orElseThrow());
    }

    @Transactional
    public void revoke(Jwt jwt, UUID id) {
        UserEntity admin = requireAdmin(jwt);
        int n = repository.revoke(id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation déjà close");
        }
        auditService.record(admin.getId(), false, AuditActions.INVITATION_REVOKED, "invitation", id, Map.of(), null);
    }

    @Transactional
    public int expireDue() {
        List<UUID> ids = repository.expireDue(clock.instant());
        for (UUID id : ids) {
            auditService.record(
                    null, true, AuditActions.INVITATION_EXPIRED, "invitation", id,
                    Map.of("id", id.toString()), null);
        }
        if (!ids.isEmpty()) {
            log.info("invitations expirées: {}", ids.size());
        }
        return ids.size();
    }

    @Transactional
    public InvitationDtos.AcceptResponse accept(Jwt jwt, String rawToken) {
        if (jwt == null) {
            deny(null, null, "unauthenticated", false);
        }
        if (PatClaims.isPat(jwt)) {
            deny(jwt, null, "pat", false);
        }
        String subject = claimsMapper.subject(jwt);
        String issuer = claimsMapper.issuer(jwt);
        if (subject == null || issuer == null) {
            deny(jwt, null, "unauthenticated", false);
        }
        boolean verified = claimsMapper.emailVerified(jwt);
        String jwtEmail = claimsMapper.email(jwt);
        InvitationRow row = lookup(rawToken);
        if (row == null) {
            deny(jwt, null, "token_invalid", false);
        }
        Instant now = clock.instant();
        if ("revoked".equals(row.status())) {
            deny(jwt, row, "revoked", true);
        }
        if ("accepted".equals(row.status())) {
            deny(jwt, row, "already_accepted", true);
        }
        if ("expired".equals(row.status()) || row.expiredClock(now)) {
            deny(jwt, row, "expired", true);
        }
        if (!row.pending()) {
            deny(jwt, row, "token_invalid", false);
        }
        if (!verified) {
            deny(jwt, row, "email_not_verified", true);
        }
        if (!inviteProperties.issuerTrusted(issuer, identityProperties.getIssuerUri())) {
            deny(jwt, row, "issuer_untrusted", false);
        }
        if (jwtEmail == null || !row.email().equals(normalizeEmail(jwtEmail))) {
            deny(jwt, row, "email_mismatch", true);
        }
        if (!userRepository.findAllByEmailIgnoreCase(row.email()).isEmpty()
                || identityService.findUserByIdentity(issuer, subject).isPresent()) {
            denyIdentityConflict(jwt, row);
        }
        try {
            licenceService.assertCanCreateUser(subject);
        } catch (RuntimeException e) {
            auditDeny(jwt, row, "licence_user_limit");
            throw e;
        }

        UserEntity user = new UserEntity();
        user.setId(allocateId(subject));
        user.setSystemAccount(false);
        user.setStatus("active");
        user.setEmail(row.email());
        String name = firstNonBlank(claimsMapper.name(jwt), row.email());
        user.setDisplayName(name);
        user.setAvatarInitials(initials(name));
        user.setLastLoginAt(now);
        UserEntity saved = userRepository.save(user);
        identityService.ensurePrimaryIdentity(saved.getId(), issuer, subject);
        platformRoleService.grant(row.invitedBy(), saved.getId(), SocleRole.valueOf(row.globalRole()));
        if (row.teamId() != null && row.teamRole() != null) {
            groupService.addMemberForInvitation(
                    row.invitedBy(), row.teamId(), saved.getId(), TeamRole.parse(row.teamRole()));
        }
        int updated = repository.markAccepted(row.id(), saved.getId(), now);
        if (updated != 1) {
            throw ApiErrors.invitationRejected();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("invitationId", row.id().toString());
        meta.put("userId", saved.getId().toString());
        if (row.teamId() != null) {
            meta.put("teamId", row.teamId().toString());
        }
        meta.put("globalRole", row.globalRole());
        auditService.recordSync(
                saved.getId(), false, AuditActions.INVITATION_ACCEPTED, "invitation", row.id(), meta, null);
        return new InvitationDtos.AcceptResponse(saved.getId(), saved.getEmail());
    }

    private InvitationRow lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            hasher.matches("", null);
            return null;
        }
        byte[] hash = hasher.hash(rawToken.trim());
        InvitationRow row = repository.findByTokenHash(hash).orElse(null);
        if (!hasher.matches(rawToken.trim(), row == null ? null : row.tokenHash())) {
            return null;
        }
        return row;
    }

    private void deny(Jwt jwt, InvitationRow row, String reason, boolean ui) {
        auditDeny(jwt, row, reason);
        throw switch (reason) {
            case "expired" -> ui
                    ? ApiErrors.invitationUi(ApiErrors.INVITATION_EXPIRED, "Invitation expirée")
                    : ApiErrors.invitationRejected();
            case "already_accepted" -> ui
                    ? ApiErrors.invitationUi(ApiErrors.INVITATION_ALREADY_USED, "Invitation déjà utilisée")
                    : ApiErrors.invitationRejected();
            case "email_mismatch" -> ui
                    ? ApiErrors.invitationUi(ApiErrors.INVITATION_EMAIL_MISMATCH, "E-mail différent de l'invitation")
                    : ApiErrors.invitationRejected();
            case "email_not_verified" -> ui
                    ? ApiErrors.invitationUi(ApiErrors.INVITATION_EMAIL_UNVERIFIED, "E-mail non vérifié")
                    : ApiErrors.invitationRejected();
            default -> ApiErrors.invitationRejected();
        };
    }

    private void denyIdentityConflict(Jwt jwt, InvitationRow row) {
        auditDeny(jwt, row, "identity_conflict");
        throw new eu.socle.identity.AccessPolicyDeniedException(
                eu.socle.identity.AccessDeniedReason.IDENTITY_CONFLICT,
                "Identité déjà associée à un autre compte : contactez l'administrateur");
    }

    private void auditDeny(Jwt jwt, InvitationRow row, String reason) {
        UUID actor = null;
        try {
            if (jwt != null) {
                String iss = claimsMapper.issuer(jwt);
                String sub = claimsMapper.subject(jwt);
                if (iss != null && sub != null) {
                    actor = identityService.findUserByIdentity(iss, sub).map(UserEntity::getId).orElse(null);
                }
            }
        } catch (RuntimeException ignored) {
            // audit best-effort
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", reason);
        auditService.record(
                actor, actor == null, AuditActions.INVITATION_ACCEPT_DENIED,
                "invitation", row == null ? null : row.id(), meta, null);
    }

    private InvitationDtos.InvitationView toView(InvitationRow row) {
        return new InvitationDtos.InvitationView(
                row.id(), row.email(), row.teamId(), repository.teamName(row.teamId()),
                row.teamRole(), row.globalRole(), row.status(), row.expiresAt(),
                row.lastSentAt(), row.createdAt(), row.invitedBy(),
                repository.userDisplayName(row.invitedBy()), row.sendCount());
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (PatClaims.isPat(jwt) || !identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        String iss = claimsMapper.issuer(jwt);
        String sub = claimsMapper.subject(jwt);
        if (iss != null && sub != null) {
            var byIdentity = identityService.findUserByIdentity(iss, sub);
            if (byIdentity.isPresent()) {
                return byIdentity.get();
            }
        }
        return userRepository.findFirstByEmailIgnoreCase(
                        claimsMapper.email(jwt) == null ? "" : normalizeEmail(claimsMapper.email(jwt)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis"));
    }

    private static SocleRole parseGlobalRole(String raw) {
        if (raw == null || raw.isBlank() || "CONTRIBUTEUR".equalsIgnoreCase(raw) || "membre".equalsIgnoreCase(raw)) {
            return SocleRole.CONTRIBUTEUR;
        }
        if ("ADMINISTRATEUR_SYSTEME".equalsIgnoreCase(raw) || "administrateur".equalsIgnoreCase(raw)) {
            return SocleRole.ADMINISTRATEUR_SYSTEME;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "globalRole invalide");
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
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
        return "?";
    }
}
