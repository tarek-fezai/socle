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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Indices de l'assistant d'écriture : liens (transclusion) cassés et paragraphes trop longs.
 *
 * <p>Un lien est « cassé » pour l'appelant si sa cible est supprimée / absente <em>ou</em> si
 * OpenFGA {@code viewer} la refuse (BatchCheck borné). Le titre d'une cible non lisible n'est
 * jamais renvoyé : le {@code label} est le texte d'ancre du lien dans le document source
 * (déjà lisible), ou {@link #INACCESSIBLE_LABEL} s'il n'y en a pas. {@code reason} distingue
 * cible absente/supprimée ({@code deleted}) et cible non visible ({@code inaccessible}).
 * {@code accessible} est toujours {@code false} pour les entrées listées.
 */
@Service
public class WritingAssistantService {

    public static final String INACCESSIBLE_LABEL = "Document inaccessible";

    public static final String REASON_DELETED = "deleted";
    public static final String REASON_INACCESSIBLE = "inaccessible";

    /** Plafond de cibles distinctes examinées (avant BatchCheck). */
    public static final int MAX_TARGETS = DocumentRelatedLinksService.MAX_CANDIDATES_PER_DIRECTION;

    private static final String SCOPE_LABEL = "writing-assistant";
    private static final int EXCERPT_CHARS = 100;

    /** UUID dans un href (ex. {@code /docs/<uuid>}, URL absolue, uuid nu). */
    private static final Pattern UUID_IN_HREF = Pattern.compile(
            "(?i)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

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

    /**
     * @param accessible toujours {@code false} (entrée présente = lien cassé pour l'appelant)
     * @param reason     {@link #REASON_DELETED} ou {@link #REASON_INACCESSIBLE}
     */
    public record BrokenLink(UUID targetId, String label, boolean accessible, String reason) {}

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
                brokenLinks(user.getId(), documentId, body),
                longParagraphs(body, threshold));
    }

    private List<BrokenLink> brokenLinks(UUID userId, UUID documentId, Map<String, Object> body) {
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

        Map<UUID, String> anchors = extractAnchorLabels(body);

        List<BrokenLink> broken = new ArrayList<>();
        for (Map.Entry<UUID, Boolean> e : targets.entrySet()) {
            UUID targetId = e.getKey();
            if (viewable.contains(targetId)) {
                continue;
            }
            boolean isLive = Boolean.TRUE.equals(e.getValue());
            String reason = isLive ? REASON_INACCESSIBLE : REASON_DELETED;
            String anchor = anchors.getOrDefault(targetId, "").trim();
            String label = anchor.isEmpty() ? INACCESSIBLE_LABEL : anchor;
            broken.add(new BrokenLink(targetId, label, false, reason));
        }
        return List.copyOf(broken);
    }

    /**
     * Texte d'ancre du premier lien TipTap (marque {@code link} ou nœud interne) vers chaque
     * {@code targetId}. Jamais le titre de la cible. Transclusions sans texte → absentes.
     */
    static Map<UUID, String> extractAnchorLabels(Map<String, Object> body) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (body != null) {
            collectAnchors(body, out);
        }
        return out;
    }

    private static void collectAnchors(Object node, Map<UUID, String> out) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object typeRaw = map.get("type");
        String type = typeRaw == null ? "" : String.valueOf(typeRaw);

        // Marque link sur un nœud texte (ou autre inline avec marks).
        UUID fromMark = targetFromLinkMarks(map.get("marks"));
        if (fromMark != null && !out.containsKey(fromMark)) {
            StringBuilder sb = new StringBuilder();
            collectText(map, sb);
            String anchor = sb.toString().trim();
            if (!anchor.isEmpty()) {
                out.put(fromMark, anchor);
            }
        }

        // Nœud de lien interne (hors transclusion) : attrs.documentId + contenu texte.
        if (!"transclusion".equals(type)) {
            UUID fromAttrs = targetFromAttrs(map.get("attrs"));
            if (fromAttrs != null && !out.containsKey(fromAttrs)
                    && ("link".equals(type) || "internalLink".equals(type) || "docLink".equals(type))) {
                StringBuilder sb = new StringBuilder();
                collectText(map, sb);
                String anchor = sb.toString().trim();
                if (!anchor.isEmpty()) {
                    out.put(fromAttrs, anchor);
                }
            }
        }

        Object content = map.get("content");
        if (content instanceof List<?> children) {
            for (Object child : children) {
                collectAnchors(child, out);
            }
        }
    }

    private static UUID targetFromLinkMarks(Object marks) {
        if (!(marks instanceof List<?> list)) {
            return null;
        }
        for (Object mark : list) {
            if (!(mark instanceof Map<?, ?> mm)) {
                continue;
            }
            if (!"link".equals(String.valueOf(mm.get("type")))) {
                continue;
            }
            Object attrs = mm.get("attrs");
            if (attrs instanceof Map<?, ?> am) {
                UUID id = parseUuidFromHref(am.get("href"));
                if (id == null) {
                    id = parseUuid(am.get("documentId"));
                }
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private static UUID targetFromAttrs(Object attrs) {
        if (!(attrs instanceof Map<?, ?> am)) {
            return null;
        }
        UUID id = parseUuid(am.get("documentId"));
        if (id == null) {
            id = parseUuidFromHref(am.get("href"));
        }
        return id;
    }

    private static UUID parseUuidFromHref(Object href) {
        if (href == null) {
            return null;
        }
        String s = String.valueOf(href).trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = UUID_IN_HREF.matcher(s);
        if (!m.find()) {
            return null;
        }
        return parseUuid(m.group(1));
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw).trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
