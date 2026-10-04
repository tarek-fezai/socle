// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.poll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRepository;
import eu.socle.poll.PollDtos.PersonalPollVoteExport;
import eu.socle.poll.PollDtos.PollOptionResult;
import eu.socle.poll.PollDtos.PollView;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sondages TipTap : id stable dans le nœud, votes modifiables tant qu'ouvert.
 * Votes individuels jamais exposés ; totaux agrégés pour tout lecteur.
 * Voter reste permis pendant une approbation (ce n'est pas une mutation de contenu).
 */
@Service
public class PollService {

    public static final String NODE_TYPE = "poll";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final Clock clock;

    public PollService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            AuditService auditService,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Upsert des sondages présents dans le corps ; archive ceux absents (votes conservés). */
    @Transactional
    public void syncFromBody(UUID documentId, Map<String, Object> body) {
        if (documentId == null || body == null) {
            return;
        }
        Map<UUID, PollNode> found = new LinkedHashMap<>();
        collectPolls(body, found);
        Instant now = clock.instant();
        for (PollNode n : found.values()) {
            String optionsJson = toJson(n.options());
            int updated = jdbc.update("""
                    UPDATE polls
                       SET question = ?, options = ?::jsonb, archived_at = NULL, updated_at = ?
                     WHERE id = ? AND document_id = ?
                    """,
                    n.question(), optionsJson, Timestamp.from(now), n.id(), documentId);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO polls (id, document_id, question, options, created_at, updated_at)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (id) DO UPDATE
                          SET document_id = excluded.document_id,
                              question = excluded.question,
                              options = excluded.options,
                              archived_at = NULL,
                              updated_at = excluded.updated_at
                        """,
                        n.id(), documentId, n.question(), optionsJson,
                        Timestamp.from(now), Timestamp.from(now));
            }
        }
        if (found.isEmpty()) {
            jdbc.update("""
                    UPDATE polls SET archived_at = COALESCE(archived_at, ?), updated_at = ?
                     WHERE document_id = ? AND archived_at IS NULL
                    """,
                    Timestamp.from(now), Timestamp.from(now), documentId);
        } else {
            String placeholders = String.join(",", found.keySet().stream().map(id -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(Timestamp.from(now));
            args.add(Timestamp.from(now));
            args.add(documentId);
            args.addAll(found.keySet());
            jdbc.update("""
                    UPDATE polls SET archived_at = COALESCE(archived_at, ?), updated_at = ?
                     WHERE document_id = ? AND archived_at IS NULL AND id NOT IN (%s)
                    """.formatted(placeholders),
                    args.toArray());
        }
    }

    @Transactional(readOnly = true)
    public PollView get(Jwt jwt, UUID pollId) {
        var user = userSyncService.syncFromJwt(jwt);
        PollRow poll = loadPoll(pollId);
        requireViewer(user.getId(), poll.documentId());
        return toView(poll, user.getId());
    }

    @Transactional
    public PollView vote(Jwt jwt, UUID pollId, String option) {
        var user = userSyncService.syncFromJwt(jwt);
        PollRow poll = loadPoll(pollId);
        requireViewer(user.getId(), poll.documentId());
        if (poll.archivedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Sondage archivé");
        }
        if (poll.closedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sondage fermé");
        }
        if (option == null || option.isBlank() || !poll.options().contains(option)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Option invalide");
        }
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO poll_votes (poll_id, user_id, option, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (poll_id, user_id) DO UPDATE
                  SET option = excluded.option,
                      updated_at = excluded.updated_at
                """,
                pollId, user.getId(), option, Timestamp.from(now));
        return toView(poll, user.getId());
    }

    @Transactional
    public PollView close(Jwt jwt, UUID pollId) {
        var user = userSyncService.syncFromJwt(jwt);
        PollRow poll = loadPoll(pollId);
        requireEditor(user.getId(), poll.documentId());
        if (poll.closedAt() != null) {
            return toView(poll, user.getId());
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE polls SET closed_at = ?, updated_at = ? WHERE id = ? AND closed_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), pollId);
        auditService.record(
                user.getId(), false, AuditActions.POLL_CLOSED,
                "document", poll.documentId(),
                Map.of("pollId", pollId.toString()), null);
        return toView(loadPoll(pollId), user.getId());
    }

    @Transactional(readOnly = true)
    public List<PersonalPollVoteExport> exportPersonalVotes(UUID userId) {
        return jdbc.query("""
                SELECT v.poll_id, p.document_id, p.question, v.option, v.updated_at
                  FROM poll_votes v
                  JOIN polls p ON p.id = v.poll_id
                 WHERE v.user_id = ?
                 ORDER BY v.updated_at DESC
                """,
                (rs, i) -> new PersonalPollVoteExport(
                        (UUID) rs.getObject("poll_id"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("question"),
                        rs.getString("option"),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
    }

    private PollView toView(PollRow poll, UUID userId) {
        String myVote = jdbc.query(
                "SELECT option FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                poll.id(), userId);
        Map<String, Long> counts = new HashMap<>();
        for (String opt : poll.options()) {
            counts.put(opt, 0L);
        }
        jdbc.query(
                "SELECT option, count(*) AS c FROM poll_votes WHERE poll_id = ? GROUP BY option",
                rs -> {
                    while (rs.next()) {
                        String opt = rs.getString("option");
                        if (counts.containsKey(opt)) {
                            counts.put(opt, rs.getLong("c"));
                        }
                    }
                    return null;
                },
                poll.id());
        List<PollOptionResult> results = poll.options().stream()
                .map(o -> new PollOptionResult(o, counts.getOrDefault(o, 0L)))
                .toList();
        long total = results.stream().mapToLong(PollOptionResult::count).sum();
        return new PollView(
                poll.id(),
                poll.documentId(),
                poll.question(),
                poll.options(),
                poll.closedAt() != null,
                poll.closedAt(),
                myVote,
                results,
                total);
    }

    private PollRow loadPoll(UUID pollId) {
        List<PollRow> rows = jdbc.query("""
                SELECT id, document_id, question, options::text AS options_json, closed_at, archived_at
                  FROM polls WHERE id = ?
                """,
                (rs, i) -> new PollRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("question"),
                        fromJson(rs.getString("options_json")),
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                        rs.getTimestamp("archived_at") != null ? rs.getTimestamp("archived_at").toInstant() : null),
                pollId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sondage introuvable");
        }
        return rows.getFirst();
    }

    private void requireViewer(UUID userId, UUID documentId) {
        documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (!authorizationService.hasRelation(userId, "document", documentId, "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
    }

    private void requireEditor(UUID userId, UUID documentId) {
        documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        authorizationService.requireDocumentRelation(userId, documentId, "editor");
    }

    @SuppressWarnings("unchecked")
    private static void collectPolls(Object node, Map<UUID, PollNode> out) {
        if (!(node instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        if (NODE_TYPE.equals(String.valueOf(map.get("type")))) {
            Object attrsObj = map.get("attrs");
            if (attrsObj instanceof Map<?, ?> attrs) {
                Object idObj = attrs.get("id");
                Object qObj = attrs.get("question");
                Object optObj = attrs.get("options");
                if (idObj instanceof String idStr) {
                    try {
                        UUID id = UUID.fromString(idStr);
                        String question = qObj instanceof String s ? s : "";
                        List<String> options = new ArrayList<>();
                        if (optObj instanceof List<?> list) {
                            for (Object o : list) {
                                if (o instanceof String s && !s.isBlank()) {
                                    options.add(s);
                                }
                            }
                        }
                        if (!question.isBlank() && options.size() >= 2) {
                            out.put(id, new PollNode(id, question, List.copyOf(options)));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // id invalide — ignoré (la validation TipTap refuse déjà)
                    }
                }
            }
        }
        Object content = map.get("content");
        if (content instanceof List<?> list) {
            for (Object child : list) {
                collectPolls(child, out);
            }
        }
    }

    private String toJson(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private record PollNode(UUID id, String question, List<String> options) {}

    private record PollRow(
            UUID id,
            UUID documentId,
            String question,
            List<String> options,
            Instant closedAt,
            Instant archivedAt
    ) {}
}
