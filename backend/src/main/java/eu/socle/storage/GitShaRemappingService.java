// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remappe les SHA Git persistés en base après une réécriture d'historique.
 *
 * <p>Colonnes couvertes (grep schéma + code, aucun autre stockage SQL de commit SHA) :
 * <ul>
 *   <li>{@code documents.git_head_sha}</li>
 *   <li>{@code document_versions.git_commit_sha}</li>
 *   <li>{@code approval_requests.submitted_git_head_sha} (V38)</li>
 * </ul>
 * Exports, attestations, webhooks et caches ne mémorisent pas de SHA Git en base.
 */
@Service
public class GitShaRemappingService {

    public static final List<String> REMAPPED_COLUMNS = List.of(
            "documents.git_head_sha",
            "document_versions.git_commit_sha",
            "approval_requests.submitted_git_head_sha");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public GitShaRemappingService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Remappe ancien SHA → nouveau SHA pour toutes les colonnes listées ci-dessus. */
    public void remap(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(mapping.size());
        mapping.forEach((oldSha, newSha) -> args.add(new Object[] {newSha, oldSha}));
        requiresNew.executeWithoutResult(status -> {
            jdbc.batchUpdate(
                    "UPDATE document_versions SET git_commit_sha = ? WHERE git_commit_sha = ?", args);
            jdbc.batchUpdate(
                    "UPDATE documents SET git_head_sha = ? WHERE git_head_sha = ?", args);
            jdbc.batchUpdate(
                    "UPDATE approval_requests SET submitted_git_head_sha = ? "
                            + "WHERE submitted_git_head_sha = ?", args);
        });
    }
}
