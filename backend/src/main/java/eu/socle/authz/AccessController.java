// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupService;
import eu.socle.user.UserSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grant/revoke d'accès — cohérence OpenFGA ↔ audit (option A : audit-first).
 * Voir docs/audit-logging.md § « cohérence grant/revoke ↔ audit ».
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    /** Préfixe log pour alerte d'incohérence FGA/audit (double échec de compensation). */
    public static final String ALERT_CRITICAL = "ALERT CRITICAL";

    private final AuthorizationService authorizationService;
    private final UserSyncService userSyncService;
    private final AuditService auditService;
    private final GroupService groupService;
    private final IdentityFacade identityFacade;

    public AccessController(
            AuthorizationService authorizationService,
            UserSyncService userSyncService,
            AuditService auditService,
            GroupService groupService,
            IdentityFacade identityFacade
    ) {
        this.authorizationService = authorizationService;
        this.userSyncService = userSyncService;
        this.auditService = auditService;
        this.groupService = groupService;
        this.identityFacade = identityFacade;
    }

    @GetMapping("/{objectType}/{objectId}")
    public AccessListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String objectType,
            @PathVariable UUID objectId
    ) {
        var user = userSyncService.syncFromJwt(jwt);
        requireCanManage(jwt, user.getId(), objectType, objectId);
        List<AuthorizationService.AccessEntry> entries =
                authorizationService.listAccessEntries(objectType, objectId);
        return new AccessListResponse(objectType, objectId, true, entries);
    }

    @PostMapping("/{objectType}/{objectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String objectType,
            @PathVariable UUID objectId,
            @Valid @RequestBody PermissionRequest request
    ) {
        var user = userSyncService.syncFromJwt(jwt);
        requireCanManage(jwt, user.getId(), objectType, objectId);
        applyAccessChange(
                user.getId(),
                objectType,
                objectId,
                request,
                true
        );
    }

    @DeleteMapping("/{objectType}/{objectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String objectType,
            @PathVariable UUID objectId,
            @Valid @RequestBody PermissionRequest request
    ) {
        var user = userSyncService.syncFromJwt(jwt);
        requireCanManage(jwt, user.getId(), objectType, objectId);
        applyAccessChange(
                user.getId(),
                objectType,
                objectId,
                request,
                false
        );
    }

    /**
     * Option A — ordre :
     * <ol>
     *   <li>{@code *.requested} (audit sync) — si échec → 503, FGA inchangé</li>
     *   <li>écriture / suppression tuple OpenFGA — si échec → erreur FGA (tentative tracée)</li>
     *   <li>{@code access.granted|revoked} (audit sync) — si échec → compensation FGA puis 503</li>
     * </ol>
     * 2xx ⇒ FGA + ligne applied présentes. 5xx ⇒ pas de changement FGA persistant non tracé.
     */
    private void applyAccessChange(
            UUID actorId,
            String objectType,
            UUID objectId,
            PermissionRequest request,
            boolean grant
    ) {
        if ("group".equalsIgnoreCase(request.subjectType())) {
            groupService.requireExists(request.subjectId());
        }

        String requestedAction = grant
                ? AuditActions.ACCESS_GRANT_REQUESTED
                : AuditActions.ACCESS_REVOKE_REQUESTED;
        String appliedAction = grant
                ? AuditActions.ACCESS_GRANTED
                : AuditActions.ACCESS_REVOKED;

        try {
            auditService.recordSync(
                    actorId,
                    false,
                    requestedAction,
                    objectType,
                    objectId,
                    meta(request, "pending"),
                    null
            );
        } catch (AuditService.AuditWriteException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Journal d'audit indisponible — aucun changement d'autorisation effectué",
                    e);
        }

        try {
            if (grant) {
                authorizationService.grantPermission(
                        objectType, objectId, request.relation(), request.subjectType(), request.subjectId());
            } else {
                authorizationService.revokePermission(
                        objectType, objectId, request.relation(), request.subjectType(), request.subjectId());
            }
        } catch (RuntimeException e) {
            // Tentative tracée (requested) ; FGA n'a pas changé (ou a échoué) — propager
            throw e;
        }

        try {
            auditService.recordSync(
                    actorId,
                    false,
                    appliedAction,
                    objectType,
                    objectId,
                    meta(request, "applied"),
                    null
            );
        } catch (AuditService.AuditWriteException e) {
            compensateFga(grant, objectType, objectId, request, e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Journal d'audit indisponible après OpenFGA — changement d'autorisation annulé",
                    e);
        }
    }

    /**
     * Annule le changement FGA après échec de l'audit « applied ».
     * Si la compensation échoue aussi → {@link #ALERT_CRITICAL} (intervention manuelle).
     */
    private void compensateFga(
            boolean wasGrant,
            String objectType,
            UUID objectId,
            PermissionRequest request,
            AuditService.AuditWriteException auditFailure
    ) {
        try {
            if (wasGrant) {
                authorizationService.revokePermission(
                        objectType, objectId, request.relation(), request.subjectType(), request.subjectId());
            } else {
                authorizationService.grantPermission(
                        objectType, objectId, request.relation(), request.subjectType(), request.subjectId());
            }
            log.warn("Compensation OpenFGA OK après échec audit applied ({} {} {} {} {})",
                    wasGrant ? "revoke-after-grant" : "re-grant-after-revoke",
                    objectType, objectId, request.relation(), request.subjectId());
        } catch (RuntimeException compensationFailure) {
            log.error(
                    "{} : compensation OpenFGA échouée après échec audit applied — "
                            + "état potentiellement incohérent (object={}:{} relation={} subject={}:{}). "
                            + "Rattrapage manuel : vérifier le tuple OpenFGA et l'absence de ligne access.{} ; "
                            + "corriger le tuple puis rejouer l'opération ou écrire l'audit manuellement.",
                    ALERT_CRITICAL,
                    objectType,
                    objectId,
                    request.relation(),
                    request.subjectType(),
                    request.subjectId(),
                    wasGrant ? "granted" : "revoked",
                    compensationFailure
            );
            log.error("{} cause audit initiale", ALERT_CRITICAL, auditFailure);
        }
    }

    private static Map<String, Object> meta(PermissionRequest request, String outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjectType", request.subjectType());
        m.put("subjectId", request.subjectId().toString());
        m.put("relation", request.relation());
        m.put("outcome", outcome);
        return m;
    }

    private void requireCanManage(Jwt jwt, UUID userId, String objectType, UUID objectId) {
        if (isSystemAdmin(jwt)) {
            return;
        }
        if ("group".equals(objectType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gestion groupe réservée aux admins");
        }
        if (!authorizationService.hasRelation(userId, objectType, objectId, "owner")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seul un owner peut gérer les accès");
        }
    }

    private boolean isSystemAdmin(Jwt jwt) {
        return identityFacade.isSystemAdmin(jwt);
    }

    public record PermissionRequest(
            @NotBlank String relation,
            @NotBlank String subjectType,
            @NotNull UUID subjectId
    ) {}

    public record AccessListResponse(
            String objectType,
            UUID objectId,
            boolean canManage,
            List<AuthorizationService.AccessEntry> entries
    ) {}
}
