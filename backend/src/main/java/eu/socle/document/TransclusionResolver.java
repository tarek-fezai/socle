package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.storage.DocumentStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Résolution dynamique des blocs TipTap {@code type: "transclusion"}.
 *
 * <p>Limite : profondeur max {@value #MAX_DEPTH} ; cycles détectés via pile d'IDs.
 * OpenFGA {@code viewer} + {@link ExternalReferencePolicy} pour les arêtes inter-workspace.
 * Voir {@code docs/transclusion.md}.
 */
@Service
public class TransclusionResolver {

    /** Profondeur max de chaînes A→B→C… (la page racine compte comme profondeur 0). */
    public static final int MAX_DEPTH = 5;

    public static final String NODE_TYPE = "transclusion";
    public static final String ATTR_DOCUMENT_ID = "documentId";
    public static final String ATTR_ACCESSIBLE = "accessible";
    public static final String ATTR_DENIED_REASON = "deniedReason";
    public static final String ATTR_TITLE = "title";
    public static final String ATTR_CYCLE = "cycle";
    public static final String ATTR_DEPTH_EXCEEDED = "depthExceeded";

    public static final String DENIED_FORBIDDEN = "forbidden";
    public static final String DENIED_MISSING = "missing";
    public static final String DENIED_CYCLE = "cycle";
    public static final String DENIED_DEPTH = "depth_exceeded";

    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final AuthorizationService authorizationService;
    private final ExternalReferencePolicy externalReferencePolicy;
    private final ExternalReferenceNotify externalReferenceNotifier;

    public TransclusionResolver(
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            AuthorizationService authorizationService,
            ExternalReferencePolicy externalReferencePolicy,
            ExternalReferenceNotify externalReferenceNotifier
    ) {
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.authorizationService = authorizationService;
        this.externalReferencePolicy = externalReferencePolicy;
        this.externalReferenceNotifier = externalReferenceNotifier;
    }

    /**
     * Extraction des ids cibles de transclusion dans un body TipTap, à toutes les
     * profondeurs (listes, citations/blockquotes, tableaux, etc.).
     * Point de vérité unique partagé avec le graphe et {@code document_links}.
     */
    public List<UUID> extractDirectTargets(Map<String, Object> body) {
        LinkedHashSet<UUID> targets = new LinkedHashSet<>();
        collectTargets(body, targets);
        return List.copyOf(targets);
    }

    /**
     * Même politique que la résolution : une arête source→cible est-elle exposable
     * (OpenFGA hors scope ici — uniquement {@code external_reference} inter-workspace) ?
     * Délégué à {@link ExternalReferencePolicy#allowsInterWorkspaceEdge}.
     */
    public boolean allowsInterWorkspaceEdge(UUID sourceSpaceId, UUID targetSpaceId) {
        return externalReferencePolicy.allowsInterWorkspaceEdge(sourceSpaceId, targetSpaceId);
    }

    @SuppressWarnings("unchecked")
    private void collectTargets(Map<String, Object> node, Set<UUID> out) {
        if (node == null) {
            return;
        }
        if (NODE_TYPE.equals(String.valueOf(node.getOrDefault("type", "")))) {
            UUID id = parseUuid(attrsOf(node).get(ATTR_DOCUMENT_ID));
            if (id != null) {
                out.add(id);
            }
            // Ne pas descendre dans content (contenu résolu éventuel) — index = cible directe.
            return;
        }
        Object content = node.get("content");
        if (content instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> m) {
                    collectTargets((Map<String, Object>) m, out);
                } else if (child instanceof List<?> nested) {
                    for (Object n : nested) {
                        if (n instanceof Map<?, ?> nm) {
                            collectTargets((Map<String, Object>) nm, out);
                        }
                    }
                }
            }
        }
    }

    /**
     * Normalise le body à l'écriture : chaque nœud {@code transclusion} devient
     * {@code {type, attrs:{documentId}}} sans {@code content} (ni attrs annexes).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizeForStorage(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        return (Map<String, Object>) normalizeNode(body);
    }

    @SuppressWarnings("unchecked")
    private Object normalizeNode(Object node) {
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object child : list) {
                out.add(normalizeNode(child));
            }
            return out;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            return node;
        }
        Map<String, Object> m = new HashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (NODE_TYPE.equals(String.valueOf(m.getOrDefault("type", "")))) {
            Map<String, Object> attrs = attrsOf(m);
            Object docId = attrs.get(ATTR_DOCUMENT_ID);
            Map<String, Object> cleanAttrs = new HashMap<>();
            if (docId != null) {
                cleanAttrs.put(ATTR_DOCUMENT_ID, String.valueOf(docId));
            }
            Map<String, Object> clean = new HashMap<>();
            clean.put("type", NODE_TYPE);
            clean.put("attrs", cleanAttrs);
            return clean;
        }
        Object content = m.get("content");
        if (content != null) {
            m.put("content", normalizeNode(content));
        }
        return m;
    }

    /**
     * Résout récursivement les blocs transclusion. Les nœuds refusés n'exposent
     * ni titre ni contenu cible (OpenFGA ou {@code external_reference=restricted}).
     */
    public Map<String, Object> resolve(
            UUID readerId,
            UUID rootDocumentId,
            Map<String, Object> body
    ) {
        DocumentEntity root = documentRepository.findActiveById(rootDocumentId).orElse(null);
        UUID rootSpaceId = root != null ? root.getSpaceId() : null;
        Set<UUID> stack = new LinkedHashSet<>();
        stack.add(rootDocumentId);
        return resolveNode(readerId, body, stack, 0, rootSpaceId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveNode(
            UUID readerId,
            Map<String, Object> node,
            Set<UUID> stack,
            int depth,
            UUID currentSpaceId
    ) {
        if (node == null) {
            return Map.of();
        }
        Map<String, Object> out = new HashMap<>(node);
        String type = String.valueOf(node.getOrDefault("type", ""));

        if (NODE_TYPE.equals(type)) {
            return resolveTransclusion(readerId, node, stack, depth, currentSpaceId);
        }

        Object content = node.get("content");
        if (content instanceof List<?> children) {
            List<Object> resolved = new ArrayList<>(children.size());
            for (Object child : children) {
                if (child instanceof Map<?, ?> m) {
                    resolved.add(resolveNode(
                            readerId, (Map<String, Object>) m, stack, depth, currentSpaceId));
                } else {
                    resolved.add(child);
                }
            }
            out.put("content", resolved);
        }
        return out;
    }

    private Map<String, Object> resolveTransclusion(
            UUID readerId,
            Map<String, Object> node,
            Set<UUID> stack,
            int depth,
            UUID currentSpaceId
    ) {
        Map<String, Object> attrs = attrsOf(node);
        UUID targetId = parseUuid(attrs.get(ATTR_DOCUMENT_ID));

        Map<String, Object> out = new HashMap<>();
        out.put("type", NODE_TYPE);
        Map<String, Object> outAttrs = new HashMap<>();

        if (targetId == null) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_MISSING);
            out.put("attrs", outAttrs);
            return out;
        }

        if (depth >= MAX_DEPTH) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_DEPTH);
            outAttrs.put(ATTR_DEPTH_EXCEEDED, true);
            out.put("attrs", outAttrs);
            return out;
        }

        if (stack.contains(targetId)) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_CYCLE);
            outAttrs.put(ATTR_CYCLE, true);
            out.put("attrs", outAttrs);
            return out;
        }

        if (!authorizationService.hasRelation(readerId, "document", targetId, "viewer")) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_FORBIDDEN);
            out.put("attrs", outAttrs);
            return out;
        }

        Optional<DocumentEntity> opt = documentRepository.findActiveById(targetId);
        if (opt.isEmpty()) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_MISSING);
            out.put("attrs", outAttrs);
            return out;
        }

        DocumentEntity target = opt.get();

        // Couche supplémentaire : external_reference sur l'espace CIBLE (inter seulement)
        if (currentSpaceId != null
                && !externalReferencePolicy.allowsInterWorkspaceEdge(currentSpaceId, target.getSpaceId())) {
            outAttrs.put(ATTR_ACCESSIBLE, false);
            outAttrs.put(ATTR_DENIED_REASON, DENIED_FORBIDDEN);
            out.put("attrs", outAttrs);
            return out;
        }

        // Notification 1ʳᵉ référence : DocumentLinkService à l'écriture (document_links).

        Map<String, Object> rawBody = documentStore.readCurrentContent(targetId, target.getBody());

        Set<UUID> nextStack = new LinkedHashSet<>(stack);
        nextStack.add(targetId);
        Map<String, Object> resolvedBody = resolveNode(
                readerId, rawBody, nextStack, depth + 1, target.getSpaceId());

        outAttrs.put(ATTR_ACCESSIBLE, true);
        outAttrs.put(ATTR_DOCUMENT_ID, targetId.toString());
        outAttrs.put(ATTR_TITLE, target.getTitle());
        out.put("attrs", outAttrs);
        out.put("content", List.of(resolvedBody));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrsOf(Map<String, Object> node) {
        Object attrs = node.get("attrs");
        if (attrs instanceof Map<?, ?> m) {
            return new HashMap<>((Map<String, Object>) m);
        }
        return new HashMap<>();
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
