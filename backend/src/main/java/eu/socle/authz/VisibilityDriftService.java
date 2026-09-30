package eu.socle.authz;

import eu.socle.document.DocumentVisibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Diagnostic lecture seule : divergence colonne {@code documents.visibility} ↔ tuples OpenFGA,
 * et {@code direct_access} orphelins (sans owner/editor/viewer direct correspondant).
 * Réparation visibility : réappliquer {@link AuthorizationService#applyVisibilityTuples}.
 * Réparation direct_access : {@link #repairOrphanDirectAccess()}.
 */
@Service
public class VisibilityDriftService {

    private static final Set<String> DIRECT_GRANTS = Set.of("owner", "editor", "viewer");

    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbc;

    public VisibilityDriftService(AuthorizationService authorizationService, JdbcTemplate jdbc) {
        this.authorizationService = authorizationService;
        this.jdbc = jdbc;
    }

    public DriftReport listDrift() {
        List<DocRow> docs = loadDocs();
        List<DriftItem> items = new ArrayList<>();
        for (DocRow doc : docs) {
            String vis = doc.visibility() == null ? DocumentVisibility.SPACE : doc.visibility();
            List<AuthorizationService.AccessTuple> tuples =
                    authorizationService.readAllTuples("document", doc.id());

            boolean hasOrgViewer = tuples.stream().anyMatch(t ->
                    AuthorizationService.WILDCARD_USER.equals(t.user())
                            && "viewer".equals(t.relation()));
            boolean hasInherit = tuples.stream().anyMatch(t -> "inherit_from".equals(t.relation()));
            String expectedParent = doc.folderId() != null
                    ? "folder:" + doc.folderId()
                    : "space:" + doc.spaceId();

            boolean expectOrg = DocumentVisibility.isOrganisationWide(vis);
            boolean expectInherit = DocumentVisibility.inheritsFromParent(vis);

            List<String> issues = new ArrayList<>();
            if (expectOrg && !hasOrgViewer) {
                issues.add("missing_user_star_viewer");
            }
            if (!expectOrg && hasOrgViewer) {
                issues.add("unexpected_user_star_viewer");
            }
            if (expectInherit && !hasInherit) {
                issues.add("missing_inherit_from");
            }
            if (!expectInherit && hasInherit) {
                issues.add("unexpected_inherit_from");
            }

            if (!issues.isEmpty()) {
                items.add(new DriftItem(
                        doc.id(),
                        vis,
                        expectOrg,
                        hasOrgViewer,
                        expectInherit,
                        hasInherit,
                        expectedParent,
                        List.copyOf(issues)));
            }
        }
        return new DriftReport(docs.size(), items.size(), List.copyOf(items));
    }

    /** {@code direct_access} sans owner/editor/viewer direct pour le même sujet. */
    public DirectAccessDriftReport listDirectAccessDrift() {
        List<DocRow> docs = loadDocs();
        List<OrphanDirectAccess> orphans = new ArrayList<>();
        int scannedTuples = 0;
        for (DocRow doc : docs) {
            List<AuthorizationService.AccessTuple> tuples =
                    authorizationService.readAllTuples("document", doc.id());
            for (AuthorizationService.AccessTuple t : tuples) {
                if (!AuthorizationService.RELATION_DIRECT_ACCESS.equals(t.relation())) {
                    continue;
                }
                scannedTuples++;
                boolean hasGrant = tuples.stream().anyMatch(g ->
                        t.user().equals(g.user()) && DIRECT_GRANTS.contains(g.relation()));
                if (!hasGrant) {
                    orphans.add(new OrphanDirectAccess(doc.id(), t.user(), t.object()));
                }
            }
        }
        return new DirectAccessDriftReport(docs.size(), scannedTuples, orphans.size(), List.copyOf(orphans));
    }

    /** Supprime les {@code direct_access} orphelins (best-effort, hors chemin critique). */
    public DirectAccessRepairReport repairOrphanDirectAccess() {
        DirectAccessDriftReport drift = listDirectAccessDrift();
        int deleted = 0;
        int errors = 0;
        for (OrphanDirectAccess o : drift.orphans()) {
            try {
                authorizationService.deleteDirectAccessTuple(o.user(), o.documentId());
                deleted++;
            } catch (RuntimeException e) {
                errors++;
            }
        }
        return new DirectAccessRepairReport(drift.orphanCount(), deleted, errors);
    }

    private List<DocRow> loadDocs() {
        return jdbc.query(
                """
                SELECT id, visibility, space_id, folder_id
                  FROM documents
                 WHERE deleted_at IS NULL
                """,
                (rs, i) -> new DocRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("visibility"),
                        (UUID) rs.getObject("space_id"),
                        (UUID) rs.getObject("folder_id")));
    }

    public record DriftReport(int documentsScanned, int driftCount, List<DriftItem> items) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentsScanned", documentsScanned);
            m.put("driftCount", driftCount);
            m.put("items", items);
            m.put("repairHint",
                    "Réappliquer applyVisibilityTuples depuis documents.visibility "
                            + "(PUT /documents/{id}/visibility avec la même valeur après correction FGA, "
                            + "ou script admin). Ne pas élargir la colonne sans aligner les tuples.");
            return m;
        }
    }

    public record DriftItem(
            UUID documentId,
            String visibility,
            boolean expectOrganisationViewer,
            boolean hasOrganisationViewer,
            boolean expectInheritFrom,
            boolean hasInheritFrom,
            String expectedParentObject,
            List<String> issues
    ) {}

    public record DirectAccessDriftReport(
            int documentsScanned,
            int directAccessTuplesScanned,
            int orphanCount,
            List<OrphanDirectAccess> orphans
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentsScanned", documentsScanned);
            m.put("directAccessTuplesScanned", directAccessTuplesScanned);
            m.put("orphanCount", orphanCount);
            m.put("orphans", orphans);
            m.put("repairHint",
                    "POST /api/v1/admin/authz/direct-access-drift/repair — "
                            + "supprime les tuples direct_access sans owner/editor/viewer direct. "
                            + "Pas un sujet de sécurité (direct_access n'accorde aucun droit).");
            return m;
        }
    }

    public record OrphanDirectAccess(UUID documentId, String user, String object) {}

    public record DirectAccessRepairReport(int orphansFound, int deleted, int errors) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orphansFound", orphansFound);
            m.put("deleted", deleted);
            m.put("errors", errors);
            return m;
        }
    }

    private record DocRow(UUID id, String visibility, UUID spaceId, UUID folderId) {}
}
