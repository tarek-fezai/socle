// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Périmètre de présélection SQL avant vérification OpenFGA.
 * Appliqué en AND avec le prédicat lisible (organisation / S_view / S_owner / D_direct / F_view).
 *
 * <p>{@link #global()} — cas explicitement instance-wide (ex. liste {@code /documents} paginée).
 * Préférer un périmètre ({@link #space}, {@link #folder}, {@link #tag}, {@link #ids}) dès que possible.
 */
public record DocumentScope(
        UUID spaceId,
        UUID folderId,
        UUID tagId,
        List<UUID> ids
) {
    public DocumentScope {
        ids = ids == null ? null : List.copyOf(ids);
    }

    /** Périmètre instance entière — documenté, à éviter hors pagination globale. */
    public static DocumentScope global() {
        return new DocumentScope(null, null, null, null);
    }

    public static DocumentScope space(UUID spaceId) {
        if (spaceId == null) {
            throw new IllegalArgumentException("spaceId requis");
        }
        return new DocumentScope(spaceId, null, null, null);
    }

    public static DocumentScope folder(UUID folderId) {
        if (folderId == null) {
            throw new IllegalArgumentException("folderId requis");
        }
        return new DocumentScope(null, folderId, null, null);
    }

    public static DocumentScope tag(UUID tagId) {
        if (tagId == null) {
            throw new IllegalArgumentException("tagId requis");
        }
        return new DocumentScope(null, null, tagId, null);
    }

    public static DocumentScope ids(Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return new DocumentScope(null, null, null, List.of());
        }
        return new DocumentScope(null, null, null, List.copyOf(documentIds));
    }

    public boolean isGlobal() {
        return spaceId == null
                && folderId == null
                && tagId == null
                && (ids == null || ids.isEmpty());
    }

    public boolean isEmptyIds() {
        return ids != null && ids.isEmpty();
    }

    public String label() {
        if (spaceId != null) {
            return "space:" + spaceId;
        }
        if (folderId != null) {
            return "folder:" + folderId;
        }
        if (tagId != null) {
            return "tag:" + tagId;
        }
        if (ids != null) {
            return "ids:" + ids.size();
        }
        return "global";
    }
}
