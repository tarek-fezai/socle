// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Indices de l'assistant d'écriture : liens (transclusion) cassés et paragraphes trop longs.
 *
 * <p>Un lien est « cassé » pour l'appelant si sa cible est supprimée / absente <em>ou</em> si
 * OpenFGA {@code viewer} la refuse (BatchCheck borné). Le titre d'une cible non lisible n'est
 * jamais renvoyé : libellé fixe {@link #INACCESSIBLE_LABEL}. Les cibles lisibles ne sont pas
 * listées (elles ne sont pas cassées).
 */
@Service
public class WritingAssistantService {

    public static final String INACCESSIBLE_LABEL = "Document inaccessible";

    /** Plafond de cibles distinctes examinées (avant BatchCheck). */
    public static final int MAX_TARGETS = DocumentRelatedLinksService.MAX_CANDIDATES_PER_DIRECTION;

    private static final String SCOPE_LABEL = "writing-assistant";
    private static final int EXCERPT_CHARS = 100;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final WritingAssistantProperties properties;

    public WritingAssistantService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            WritingAssistantProperties properties
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.properties = properties;
    }

    public record BrokenLink(UUID targetId, String label, boolean accessible) {}

    /**
     * @param index    rang du paragraphe (0-based, ordre du document, tous paragraphes confondus)
     * @param excerpt  début du texte (le lecteur a déjà le droit de lire le document)
     */
    public record LongParagraph(int index, int wordCount, String excerpt) {}

    public record Hints(
            int longParagraphThresholdWords,
            List<BrokenLink> brokenLinks,
            List<LongParagraph> longParagraphs
    ) {}

    @Transactional(readOnly = true)
    public Hints hints(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        DocumentEntity doc = documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");

        int threshold = properties.getLongParagraphWords();
        Map<String, Object> body = documentStore.readCurrentContent(doc.getId(), doc.getBody());
        return new Hints(
                threshold,
                brokenLinks(user.getId(), documentId),
                longParagraphs(body, threshold));
    }

    private List<BrokenLink> brokenLinks(UUID userId, UUID documentId) {
        // Cible → vivante (existe et non supprimée). LEFT JOIN : document_links.target_id sans FK.
        Map<UUID, Boolean> targets = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT dl.target_id,
                       (d.id IS NOT NULL AND d.deleted_at IS NULL) AS live
                  FROM document_links dl
                  LEFT JOIN documents d ON d.id = dl.target_id
                 WHERE dl.source_id = ? AND dl.target_id <> ?
                 ORDER BY dl.target_id
                 LIMIT ?
                """,
                (rs, i) -> {
                    targets.put((UUID) rs.getObject("target_id"), rs.getBoolean("live"));
                    return null;
                },
                documentId, documentId, MAX_TARGETS);

        Set<UUID> live = targets.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        Set<UUID> viewable = Set.copyOf(live.isEmpty()
                ? List.<UUID>of()
                : authorizationService.filterByDocumentViewer(userId, live, SCOPE_LABEL));

        List<BrokenLink> broken = new ArrayList<>();
        for (UUID targetId : targets.keySet()) {
            if (!viewable.contains(targetId)) {
                broken.add(new BrokenLink(targetId, INACCESSIBLE_LABEL, false));
            }
        }
        return List.copyOf(broken);
    }

    /**
     * Paragraphes TipTap dépassant {@code threshold} mots — corps stocké (blocs transclusion non
     * résolus : le contenu d'un autre document n'est jamais analysé ici).
     */
    static List<LongParagraph> longParagraphs(Map<String, Object> body, int threshold) {
        List<LongParagraph> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        walk(body, threshold, new int[] {0}, out);
        return List.copyOf(out);
    }

    private static void walk(Object node, int threshold, int[] counter, List<LongParagraph> out) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object type = map.get("type");
        if ("transclusion".equals(type)) {
            return;
        }
        if ("paragraph".equals(type)) {
            int index = counter[0]++;
            StringBuilder text = new StringBuilder();
            collectText(map, text);
            String plain = text.toString().trim();
            int words = plain.isEmpty() ? 0 : plain.split("\\s+").length;
            if (words > threshold) {
                out.add(new LongParagraph(index, words, excerpt(plain)));
            }
            return;
        }
        Object content = map.get("content");
        if (content instanceof List<?> children) {
            for (Object child : children) {
                walk(child, threshold, counter, out);
            }
        }
    }

    private static void collectText(Map<?, ?> node, StringBuilder sb) {
        Object type = node.get("type");
        if ("text".equals(type) && node.get("text") instanceof String s) {
            sb.append(s);
            return;
        }
        if ("hardBreak".equals(type)) {
            sb.append(' ');
            return;
        }
        if (node.get("content") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> m) {
                    collectText(m, sb);
                }
            }
        }
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS).trim() + "…";
    }
}
