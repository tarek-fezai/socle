package eu.socle.space;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Point de vérité unique pour {@code spaces.external_reference}.
 * Utilisé par résolution de transclusion ET graphe — pas de second contrôle parallèle.
 *
 * @see docs/transclusion.md
 * @see docs/spaces-governance.md
 */
@Service
public class ExternalReferencePolicy {

    public static final String OPEN = "open";
    public static final String RESTRICTED = "restricted";

    private final JdbcTemplate jdbc;

    public ExternalReferencePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code true} si une arête inter-workspace vers {@code targetSpaceId} est autorisée.
     * Intra-workspace : toujours {@code true} (ce réglage n'a aucun effet).
     */
    public boolean allowsInterWorkspaceEdge(UUID sourceSpaceId, UUID targetSpaceId) {
        if (sourceSpaceId == null || targetSpaceId == null) {
            return false;
        }
        if (sourceSpaceId.equals(targetSpaceId)) {
            return true;
        }
        return OPEN.equals(readMode(targetSpaceId));
    }

    public String readMode(UUID spaceId) {
        String mode = jdbc.query(
                "SELECT external_reference FROM spaces WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getString("external_reference") : null,
                spaceId);
        return mode == null || mode.isBlank() ? OPEN : mode;
    }

    public static boolean isValidMode(String mode) {
        return OPEN.equals(mode) || RESTRICTED.equals(mode);
    }
}
