// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserRepository;
import eu.socle.user.UserSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Soft-lock advisory en base (multi-instance). N'empêche jamais une sauvegarde —
 * seul {@link DocumentService#assertExpectedVersion} gère le conflit réel.
 */
@Service
public class EditLockService {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorizationService;
    private final UserSyncService userSyncService;
    private final UserRepository userRepository;
    private final EditLockProperties properties;
    private final Clock clock;

    @Autowired
    public EditLockService(
            JdbcTemplate jdbc,
            AuthorizationService authorizationService,
            UserSyncService userSyncService,
            UserRepository userRepository,
            EditLockProperties properties
    ) {
        this(jdbc, authorizationService, userSyncService, userRepository, properties, Clock.systemUTC());
    }

    EditLockService(
            JdbcTemplate jdbc,
            AuthorizationService authorizationService,
            UserSyncService userSyncService,
            UserRepository userRepository,
            EditLockProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorizationService = authorizationService;
        this.userSyncService = userSyncService;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EditLockView status(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        return currentView(documentId, user.getId());
    }

    /**
     * Acquisition / renouvellement heartbeat. Si un autre holder est actif, on ne vole pas
     * le lock — on renvoie son état (advisory).
     */
    @Transactional
    public EditLockView acquire(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(properties.getTtlSeconds());

        Optional<LockRow> existing = load(documentId);
        if (existing.isPresent()) {
            LockRow row = existing.get();
            if (row.heartbeatAt().isAfter(cutoff) && !row.holderUserId().equals(user.getId())) {
                return toView(row, user.getId(), false);
            }
        }

        jdbc.update("""
                INSERT INTO document_edit_locks (document_id, holder_user_id, acquired_at, heartbeat_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE SET
                    holder_user_id = EXCLUDED.holder_user_id,
                    acquired_at = CASE
                        WHEN document_edit_locks.holder_user_id = EXCLUDED.holder_user_id
                             AND document_edit_locks.heartbeat_at > ?
                        THEN document_edit_locks.acquired_at
                        ELSE EXCLUDED.acquired_at
                    END,
                    heartbeat_at = EXCLUDED.heartbeat_at
                """,
                documentId, user.getId(), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(cutoff));

        return currentView(documentId, user.getId());
    }

    @Transactional
    public EditLockView heartbeat(Jwt jwt, UUID documentId) {
        return acquire(jwt, documentId);
    }

    @Transactional
    public void release(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        jdbc.update("""
                DELETE FROM document_edit_locks
                 WHERE document_id = ? AND holder_user_id = ?
                """, documentId, user.getId());
    }

    private EditLockView currentView(UUID documentId, UUID viewerId) {
        Instant cutoff = clock.instant().minusSeconds(properties.getTtlSeconds());
        Optional<LockRow> row = load(documentId);
        if (row.isEmpty() || !row.get().heartbeatAt().isAfter(cutoff)) {
            if (row.isPresent()) {
                jdbc.update("DELETE FROM document_edit_locks WHERE document_id = ?", documentId);
            }
            return EditLockView.inactive(properties.getTtlSeconds(), properties.getHeartbeatSeconds());
        }
        return toView(row.get(), viewerId, row.get().holderUserId().equals(viewerId));
    }

    private EditLockView toView(LockRow row, UUID viewerId, boolean heldByMe) {
        String name = userRepository.findById(row.holderUserId())
                .map(UserEntity::getDisplayName)
                .orElse("Utilisateur");
        return new EditLockView(
                true,
                row.holderUserId(),
                name,
                row.acquiredAt(),
                row.heartbeatAt(),
                heldByMe || row.holderUserId().equals(viewerId),
                properties.getTtlSeconds(),
                properties.getHeartbeatSeconds()
        );
    }

    private Optional<LockRow> load(UUID documentId) {
        List<LockRow> rows = jdbc.query("""
                SELECT document_id, holder_user_id, acquired_at, heartbeat_at
                  FROM document_edit_locks WHERE document_id = ?
                """, (rs, i) -> new LockRow(
                (UUID) rs.getObject("document_id"),
                (UUID) rs.getObject("holder_user_id"),
                rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant()
        ), documentId);
        return rows.stream().findFirst();
    }

    public record EditLockView(
            boolean active,
            UUID holderUserId,
            String holderDisplayName,
            Instant acquiredAt,
            Instant heartbeatAt,
            boolean heldByCurrentUser,
            int ttlSeconds,
            int heartbeatSeconds
    ) {
        static EditLockView inactive(int ttl, int heartbeat) {
            return new EditLockView(false, null, null, null, null, false, ttl, heartbeat);
        }
    }

    private record LockRow(UUID documentId, UUID holderUserId, Instant acquiredAt, Instant heartbeatAt) {}
}
