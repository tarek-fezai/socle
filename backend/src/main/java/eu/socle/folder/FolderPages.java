// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Document servant de page d'un dossier ({@code folders.page_document_id}). */
public final class FolderPages {

    /**
     * Exclusion commune (pages de dossier et pages d'espace).
     * Préférer {@link eu.socle.document.PageDocuments#EXCLUDE_SQL}.
     */
    public static final String EXCLUDE_SQL = eu.socle.document.PageDocuments.EXCLUDE_SQL;

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
