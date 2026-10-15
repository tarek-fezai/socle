// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Document servant de page d'un dossier ({@code folders.page_document_id}). */
public final class FolderPages {

    /**
     * À conjuguer avec l'alias {@code d} de {@code documents}.
     * La page reste en base et en recherche ; elle sort des listes de documents.
     */
    public static final String EXCLUDE_SQL =
            "NOT EXISTS (SELECT 1 FROM folders fp WHERE fp.page_document_id = d.id)";

    private FolderPages() {}

    public static boolean isPage(JdbcTemplate jdbc, UUID documentId) {
        if (jdbc == null || documentId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM folders WHERE page_document_id = ?",
                Integer.class,
                documentId);
        return n != null && n > 0;
    }
}
