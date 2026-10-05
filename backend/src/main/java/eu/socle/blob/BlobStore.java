// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import java.io.InputStream;
import java.util.Optional;

/**
 * Stockage binaire d'instance (pièces jointes) — provider exclusif {@code local} ou {@code s3}.
 * Les clés sont des UUID (jamais dérivées d'un nom utilisateur).
 */
public interface BlobStore {

    /**
     * @param key       UUID sous forme canonique
     * @param content   flux à lire entièrement (appelant ferme le flux)
     * @param size      taille attendue en octets (≥ 0)
     * @param mediaType type MIME détecté côté serveur
     */
    void put(String key, InputStream content, long size, String mediaType);

    /**
     * @param range plage inclusive optionnelle ({@code from}/{@code to} en octets, style HTTP Range)
     */
    Optional<BlobObject> get(String key, Optional<ByteRange> range);

    void delete(String key);

    boolean exists(String key);

    record ByteRange(long from, long to) {
        public ByteRange {
            if (from < 0 || to < from) {
                throw new IllegalArgumentException("plage invalide: " + from + "-" + to);
            }
        }
    }

    record BlobObject(
            InputStream content,
            long contentLength,
            String mediaType,
            Optional<ByteRange> range,
            long totalSize
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            content.close();
        }
    }
}
