// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import java.util.UUID;

/** Notifie les owners à la première référence externe (paire d'espaces). */
@FunctionalInterface
public interface ExternalReferenceNotify {

    void notifyOwnersOnFirstPair(UUID sourceSpaceId, UUID targetSpaceId);
}
