// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.comment.CommentDtos.AnchorRequest;
import eu.socle.comment.CommentDtos.AnchorView;
import eu.socle.comment.CommentDtos.CommentView;
import eu.socle.comment.CommentDtos.CommentsPage;
import eu.socle.comment.CommentDtos.CreateCommentRequest;
import eu.socle.comment.CommentDtos.MentionWarning;
import eu.socle.comment.CommentDtos.PersonalCommentExport;
import eu.socle.comment.CommentDtos.UpdateCommentRequest;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.notification.NotificationService;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CommentService {

    public static final String ANONYMIZED_LABEL = "Utilisateur supprimé";
    public static final String NOTIF_TYPE_MENTION = "comment_mention";
    /** Avertissement générique — ne révèle ni existence ni accès. */
    public static final String MENTION_NO_NOTIFY =
            "Cette personne ne sera pas notifiée";
    /** Uniquement `@[Nom](uuid)` (autocomplétion) — pas de `@texte` libre. */
    private static final Pattern MENTION_REF =
            Pattern.compile("@\\[([^\\]]*)\\]\\(([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");
    private static final int MENTION_SUGGEST_MIN_PREFIX = 2;
    private static final int MENTION_SUGGEST_LIMIT = 10;
    private static final int MENTION_SUGGEST_CANDIDATE_POOL = 50;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final ReliabilityScoreService reliabilityScoreService;

    public CommentService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            NotificationService notificationService,
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            ReliabilityScoreService reliabilityScoreService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.reliabilityScoreService = reliabilityScoreService;
    }

    @Transactional(readOnly = true)
    public CommentsPage list(Jwt jwt, UUID documentId, String statusFilter, Integer version) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");
        DocumentEntity doc = requireActiveDocument(documentId);
        int versionNo = version == null || version <= 0 ? doc.getCurrentVersionNo() : version;
        Map<String, Object> body = loadBodyAtVersion(doc, versionNo);
        String plain = CommentAnchorResolver.plainText(body);

        List<CommentRow> rows = jdbc.query("""
                SELECT c.id, c.document_id, c.parent_comment_id, c.author_id,
                       COALESCE(NULLIF(c.author_display_name, ''), u.display_name) AS author_name,
                       c.author_anonymized, c.body, c.status,
                       c.deleted_at, c.deleted_by_moderator,
                       c.anchor_exact, c.anchor_prefix, c.anchor_suffix,
                       c.anchor_block_id, c.anchor_version_no,
                       c.created_at, c.updated_at, c.resolved_by, c.resolved_at
                  FROM document_comments c
                  LEFT JOIN users u ON u.id = c.author_id
                 WHERE c.document_id = ?
                 ORDER BY c.created_at ASC
                """, commentMapper(), documentId);

        Map<UUID, List<CommentRow>> byParent = new HashMap<>();
        List<CommentRow> roots = new ArrayList<>();
        for (CommentRow r : rows) {
            if (r.parentId() == null) {
                roots.add(r);
            } else {
                byParent.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r);
            }
        }

        List<CommentView> threads = new ArrayList<>();
        List<CommentView> detached = new ArrayList<>();
        int openCount = 0;
        for (CommentRow root : roots) {
            if (statusFilter != null && !statusFilter.isBlank()
                    && !statusFilter.equalsIgnoreCase(root.status())) {
                continue;
            }
            AnchorView anchor = resolveAnchor(root, plain);
            List<CommentView> replies = byParent.getOrDefault(root.id(), List.of()).stream()
                    .map(r -> toView(r, null, List.of(), List.of()))
                    .toList();
            CommentView view = toView(root, anchor, replies, List.of());
            if (root.deletedAt() == null && "ouvert".equals(root.status())) {
                openCount++;
            }
            if (anchor != null && !anchor.attached() && root.anchorExact() != null) {
                detached.add(view);
            } else {
                threads.add(view);
            }
        }
        return new CommentsPage(documentId, versionNo, threads, detached, openCount);
    }

    @Transactional
    public CommentView create(Jwt jwt, UUID documentId, CreateCommentRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireActiveDocument(documentId);
        requireCanComment(user.getId(), documentId, doc.getSpaceId());

        UUID parentId = request.parentId();
        CommentRow parent = null;
        if (parentId != null) {
            parent = requireComment(parentId);
            if (!parent.documentId().equals(documentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "parentId hors document");
            }
            if (parent.parentId() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Un seul niveau de réponses est autorisé");
            }
            if (parent.deletedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Impossible de répondre à un commentaire supprimé");
            }
        }

        String body;
        try {
            body = CommentBodySanitizer.sanitize(request.body());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        TextQuoteAnchor anchor = null;
        if (parentId == null && request.anchor() != null) {
            AnchorRequest a = request.anchor();
            Map<String, Object> content = documentStore.readCurrentContent(doc.getId(), doc.getBody());
            String plain = CommentAnchorResolver.plainText(content);
            anchor = TextQuoteAnchor.of(
                    a.exact(), a.prefix(), a.suffix(), a.blockId(), doc.getCurrentVersionNo());
            if (anchor != null && CommentAnchorResolver.resolve(plain, anchor).attached() == false
                    && plain.contains(a.exact())) {
                // exact présent mais contexte décalé — on garde l'ancre telle quelle
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_comments (
                  id, document_id, parent_comment_id, author_id, body,
                  anchor_block_id, anchor_exact, anchor_prefix, anchor_suffix, anchor_version_no,
                  status, resolved, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ouvert', false, now(), now())
                """,
                id, documentId, parentId, user.getId(), body,
                anchor != null ? anchor.blockId() : null,
                anchor != null ? anchor.exact() : null,
                anchor != null ? anchor.prefix() : null,
                anchor != null ? anchor.suffix() : null,
                anchor != null ? anchor.versionNo() : null);

        auditService.record(user.getId(), false, AuditActions.COMMENT_CREATED, "comment", id,
                Map.of("documentId", documentId.toString(),
                        "parentId", parentId != null ? parentId.toString() : "",
                        "anchored", anchor != null),
                null);

        List<MentionWarning> warnings = processMentions(user.getId(), documentId, body, id);
        reliabilityScoreService.onDocumentCommentChanged(documentId);

        CommentRow row = requireComment(id);
        Map<String, Object> content = documentStore.readCurrentContent(doc.getId(), doc.getBody());
        AnchorView av = resolveAnchor(row, CommentAnchorResolver.plainText(content));
        return toView(row, av, List.of(), warnings);
    }

    @Transactional
    public CommentView update(Jwt jwt, UUID commentId, UpdateCommentRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        CommentRow row = requireComment(commentId);
        if (row.deletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Commentaire supprimé");
        }
        if (!row.authorId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seul l'auteur peut modifier");
        }
        authorizationService.requireDocumentRelation(user.getId(), row.documentId(), "viewer");

        String body;
        try {
            body = CommentBodySanitizer.sanitize(request.body());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        jdbc.update("""
                UPDATE document_comments SET body = ?, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """, body, commentId);

        auditService.record(user.getId(), false, AuditActions.COMMENT_EDITED, "comment", commentId,
                Map.of("documentId", row.documentId().toString()), null);

        List<MentionWarning> warnings = processMentions(user.getId(), row.documentId(), body, commentId);
        CommentRow updated = requireComment(commentId);
        return toView(updated, null, List.of(), warnings);
    }

    @Transactional
    public CommentView delete(Jwt jwt, UUID commentId) {
        var user = userSyncService.syncFromJwt(jwt);
        CommentRow row = requireComment(commentId);
        if (row.deletedAt() != null) {
            return toView(row, null, List.of(), List.of());
        }
        DocumentEntity doc = requireActiveDocument(row.documentId());
        boolean author = row.authorId().equals(user.getId());
        boolean moderator = authorizationService.hasRelation(
                user.getId(), "space", doc.getSpaceId(), "owner");
        if (!author && !moderator) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Suppression réservée à l'auteur ou à un owner");
        }
        jdbc.update("""
                UPDATE document_comments
                   SET deleted_at = now(), deleted_by = ?, deleted_by_moderator = ?,
                       body = '', updated_at = now()
                 WHERE id = ?
                """, user.getId(), !author && moderator, commentId);

        auditService.record(user.getId(), false, AuditActions.COMMENT_DELETED, "comment", commentId,
                Map.of("documentId", row.documentId().toString(),
                        "moderation", !author && moderator),
                null);
        reliabilityScoreService.onDocumentCommentChanged(row.documentId());
        return toView(requireComment(commentId), null, List.of(), List.of());
    }

    @Transactional
    public CommentView resolve(Jwt jwt, UUID commentId) {
        return setStatus(jwt, commentId, "resolu", true);
    }

    @Transactional
    public CommentView reopen(Jwt jwt, UUID commentId) {
        return setStatus(jwt, commentId, "ouvert", false);
    }

    /**
     * Remplace l'auteur par « Utilisateur supprimé » (suppression de compte future).
     * Testable unitairement sans flux compte complet.
     */
    @Transactional
    public int anonymizeAuthor(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return jdbc.update("""
                UPDATE document_comments
                   SET author_anonymized = true,
                       author_display_name = ?,
                       updated_at = now()
                 WHERE author_id = ? AND author_anonymized = false
                """, ANONYMIZED_LABEL, userId);
    }

    /** Export RGPD : commentaires de l'utilisateur (corps inclus, hors documents inaccessibles n/a). */
    @Transactional(readOnly = true)
    public List<PersonalCommentExport> exportPersonalComments(UUID userId) {
        return jdbc.query("""
                SELECT id, document_id, body, status, created_at, deleted_at
                  FROM document_comments
                 WHERE author_id = ?
                 ORDER BY created_at ASC
                """,
                (rs, i) -> new PersonalCommentExport(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("body"),
                        rs.getString("status"),
                        ts(rs.getTimestamp("created_at")),
                        rs.getTimestamp("deleted_at") != null
                ),
                userId);
    }

    @Transactional(readOnly = true)
    public int countOpenThreads(UUID documentId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM document_comments
                 WHERE document_id = ?
                   AND parent_comment_id IS NULL
                   AND deleted_at IS NULL
                   AND status = 'ouvert'
                """, Integer.class, documentId);
        return n == null ? 0 : n;
    }

    // ── internes ─────────────────────────────────────────────────────

    private CommentView setStatus(Jwt jwt, UUID commentId, String status, boolean resolving) {
        var user = userSyncService.syncFromJwt(jwt);
        CommentRow row = requireComment(commentId);
        if (row.parentId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Seuls les fils (racines) peuvent être résolus");
        }
        DocumentEntity doc = requireActiveDocument(row.documentId());
        requireCanResolve(user.getId(), row, doc);

        if (resolving) {
            jdbc.update("""
                    UPDATE document_comments
                       SET status = 'resolu', resolved = true, resolved_by = ?, resolved_at = now(),
                           updated_at = now()
                     WHERE id = ?
                    """, user.getId(), commentId);
            auditService.record(user.getId(), false, AuditActions.COMMENT_RESOLVED, "comment", commentId,
                    Map.of("documentId", row.documentId().toString()), null);
        } else {
            jdbc.update("""
                    UPDATE document_comments
                       SET status = 'ouvert', resolved = false, resolved_by = NULL, resolved_at = NULL,
                           updated_at = now()
                     WHERE id = ?
                    """, commentId);
            auditService.record(user.getId(), false, AuditActions.COMMENT_REOPENED, "comment", commentId,
                    Map.of("documentId", row.documentId().toString()), null);
        }
        reliabilityScoreService.onDocumentCommentChanged(row.documentId());
        return toView(requireComment(commentId), null, List.of(), List.of());
    }

    private void requireCanResolve(UUID userId, CommentRow root, DocumentEntity doc) {
        if (root.authorId().equals(userId)) {
            return;
        }
        if (authorizationService.hasRelation(userId, "document", doc.getId(), "editor")) {
            return;
        }
        if (authorizationService.hasRelation(userId, "space", doc.getSpaceId(), "owner")) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Résolution non autorisée");
    }

    private void requireCanComment(UUID userId, UUID documentId, UUID spaceId) {
        // éditeur document
        if (authorizationService.hasRelation(userId, "document", documentId, "editor")) {
            return;
        }
        String policy = loadCommentPolicy(spaceId);
        if ("all_readers".equals(policy)) {
            authorizationService.requireDocumentRelation(userId, documentId, "viewer");
            return;
        }
        // members (défaut) : space.viewer
        if (authorizationService.hasRelation(userId, "space", spaceId, "viewer")) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Commentaire non autorisé");
    }

    private String loadCommentPolicy(UUID spaceId) {
        List<String> rows = jdbc.query(
                "SELECT comment_policy FROM spaces WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getString(1), spaceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        String p = rows.getFirst();
        return p == null || p.isBlank() ? "members" : p;
    }

    /**
     * Mentions : seules les formes {@code @[Nom](uuid)} (autocomplétion) sont résolues.
     * {@code @texte} libre reste du texte. Avertissement générique si pas de notif
     * (uuid inconnu ou sans accès — même message).
     */
    private List<MentionWarning> processMentions(
            UUID actorId, UUID documentId, String body, UUID commentId
    ) {
        List<MentionWarning> warnings = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Matcher m = MENTION_REF.matcher(body);
        while (m.find()) {
            UUID mentioned;
            try {
                mentioned = UUID.fromString(m.group(2));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!seen.add(mentioned) || mentioned.equals(actorId)) {
                continue;
            }
            boolean canRead = authorizationService.hasRelation(
                    mentioned, "document", documentId, "viewer");
            if (!canRead) {
                warnings.add(new MentionWarning(mentioned, "", MENTION_NO_NOTIFY));
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("document_id", documentId.toString());
            payload.put("comment_id", commentId.toString());
            payload.put("mentioned_by", actorId.toString());
            notificationService.create(mentioned, NOTIF_TYPE_MENTION, payload);
        }
        return warnings;
    }

    /**
     * Autocomplétion {@code @} : réservée aux commentateurs ; candidats = utilisateurs
     * pouvant <em>lire</em> le document ; préfixe ≥ 2 ; max 10.
     */
    @Transactional(readOnly = true)
    public List<CommentDtos.MentionSuggestion> suggestMentions(
            Jwt jwt, UUID documentId, String query
    ) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = requireActiveDocument(documentId);
        requireCanComment(user.getId(), documentId, doc.getSpaceId());

        String q = query == null ? "" : query.strip();
        if (q.length() < MENTION_SUGGEST_MIN_PREFIX) {
            return List.of();
        }
        String like = q.toLowerCase() + "%";
        List<MentionCandidateRow> candidates = jdbc.query("""
                SELECT id, display_name, email
                  FROM users
                 WHERE status = 'active'
                   AND (lower(display_name) LIKE ? OR lower(email) LIKE ?)
                 ORDER BY lower(display_name) ASC
                 LIMIT ?
                """,
                (rs, i) -> new MentionCandidateRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("display_name"),
                        rs.getString("email")),
                like, like, MENTION_SUGGEST_CANDIDATE_POOL);

        List<CommentDtos.MentionSuggestion> out = new ArrayList<>();
        for (MentionCandidateRow c : candidates) {
            if (c.id().equals(user.getId())) {
                continue;
            }
            if (!authorizationService.hasRelation(c.id(), "document", documentId, "viewer")) {
                continue;
            }
            out.add(new CommentDtos.MentionSuggestion(c.id(), c.displayName(), c.email()));
            if (out.size() >= MENTION_SUGGEST_LIMIT) {
                break;
            }
        }
        return out;
    }

    private DocumentEntity requireActiveDocument(UUID documentId) {
        return documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Document introuvable"));
    }

    private Map<String, Object> loadBodyAtVersion(DocumentEntity doc, int versionNo) {
        if (versionNo == doc.getCurrentVersionNo()) {
            return documentStore.readCurrentContent(doc.getId(), doc.getBody());
        }
        try {
            return documentStore.loadVersionBody(doc.getId(), versionNo);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version introuvable");
        }
    }

    private CommentRow requireComment(UUID id) {
        List<CommentRow> rows = jdbc.query("""
                SELECT c.id, c.document_id, c.parent_comment_id, c.author_id,
                       COALESCE(NULLIF(c.author_display_name, ''), u.display_name) AS author_name,
                       c.author_anonymized, c.body, c.status,
                       c.deleted_at, c.deleted_by_moderator,
                       c.anchor_exact, c.anchor_prefix, c.anchor_suffix,
                       c.anchor_block_id, c.anchor_version_no,
                       c.created_at, c.updated_at, c.resolved_by, c.resolved_at
                  FROM document_comments c
                  LEFT JOIN users u ON u.id = c.author_id
                 WHERE c.id = ?
                """, commentMapper(), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire introuvable");
        }
        return rows.getFirst();
    }

    private AnchorView resolveAnchor(CommentRow row, String plain) {
        if (row.anchorExact() == null || row.anchorExact().isBlank()) {
            return null;
        }
        TextQuoteAnchor a = TextQuoteAnchor.of(
                row.anchorExact(), row.anchorPrefix(), row.anchorSuffix(),
                row.anchorBlockId(), row.anchorVersionNo());
        var res = CommentAnchorResolver.resolve(plain, a);
        return new AnchorView(
                row.anchorExact(), row.anchorPrefix(), row.anchorSuffix(),
                row.anchorBlockId(), row.anchorVersionNo(),
                res.attached(),
                res.attached() ? res.startOffset() : null,
                res.attached() ? res.endOffset() : null);
    }

    private CommentView toView(
            CommentRow row, AnchorView anchor, List<CommentView> replies, List<MentionWarning> warnings
    ) {
        boolean deleted = row.deletedAt() != null;
        String deletedLabel = null;
        String body = row.body();
        if (deleted) {
            deletedLabel = Boolean.TRUE.equals(row.deletedByModerator())
                    ? "Commentaire supprimé (par un modérateur)"
                    : "Commentaire supprimé (par l'auteur)";
            body = "";
        }
        String authorName = Boolean.TRUE.equals(row.authorAnonymized())
                ? ANONYMIZED_LABEL
                : row.authorName();
        List<CommentView> replyViews = replies == null ? List.of() : replies;
        return new CommentView(
                row.id(), row.documentId(), row.parentId(), row.authorId(),
                authorName, Boolean.TRUE.equals(row.authorAnonymized()),
                body, row.status(), deleted, deletedLabel, anchor,
                ts(row.createdAt()), ts(row.updatedAt()),
                row.resolvedBy(), ts(row.resolvedAt()),
                replyViews, warnings == null ? List.of() : warnings);
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static String ts(Instant i) {
        return i == null ? null : i.toString();
    }

    private RowMapper<CommentRow> commentMapper() {
        return (rs, i) -> new CommentRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("document_id"),
                (UUID) rs.getObject("parent_comment_id"),
                (UUID) rs.getObject("author_id"),
                rs.getString("author_name"),
                rs.getBoolean("author_anonymized"),
                rs.getString("body"),
                rs.getString("status"),
                rs.getTimestamp("deleted_at") != null
                        ? rs.getTimestamp("deleted_at").toInstant() : null,
                rs.getBoolean("deleted_by_moderator"),
                rs.getString("anchor_exact"),
                rs.getString("anchor_prefix"),
                rs.getString("anchor_suffix"),
                rs.getString("anchor_block_id"),
                (Integer) rs.getObject("anchor_version_no"),
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null
                        ? rs.getTimestamp("updated_at").toInstant() : null,
                (UUID) rs.getObject("resolved_by"),
                rs.getTimestamp("resolved_at") != null
                        ? rs.getTimestamp("resolved_at").toInstant() : null
        );
    }

    private record CommentRow(
            UUID id, UUID documentId, UUID parentId, UUID authorId, String authorName,
            Boolean authorAnonymized, String body, String status,
            Instant deletedAt, Boolean deletedByModerator,
            String anchorExact, String anchorPrefix, String anchorSuffix,
            String anchorBlockId, Integer anchorVersionNo,
            Instant createdAt, Instant updatedAt, UUID resolvedBy, Instant resolvedAt
    ) {}

    private record MentionCandidateRow(UUID id, String displayName, String email) {}
}
