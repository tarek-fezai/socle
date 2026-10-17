// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Documents servant de page d'un dossier ou d'un espace.
 * Ils restent en base et en recherche ; ils sortent des inventaires.
 */
public final class PageDocuments {

    /**
     * À conjuguer avec l'alias {@code d} de {@code documents}.
     * Exclut les pages de dossier et les pages d'espace.
     */
    public static final String EXCLUDE_SQL = exclude("d.id");

    private PageDocuments() {}

    /** {@code documentIdExpr} est une expression SQL (colonne), jamais une valeur. */
    public static String exclude(String documentIdExpr) {
        return "NOT EXISTS (SELECT 1 FROM folders fp WHERE fp.page_document_id = " + documentIdExpr + ")"
                + " AND NOT EXISTS (SELECT 1 FROM spaces sp WHERE sp.page_document_id = " + documentIdExpr + ")";
    }

    public static boolean isSpacePage(JdbcTemplate jdbc, UUID documentId) {
        if (jdbc == null || documentId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spaces WHERE page_document_id = ?",
                Integer.class,
                documentId);
        return n != null && n > 0;
    }
}
