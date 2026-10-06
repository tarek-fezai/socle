// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Stockage local atomique (fichier temp + rename). Racine configurable ;
 * les clés UUID empêchent toute traversée hors du répertoire.
 */
public class LocalBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalBlobStore.class);

    private final Path root;

    public LocalBlobStore(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de créer le répertoire blob local: " + root, e);
        }
    }

    Path root() {
        return root;
    }

    @Override
    public void put(String key, InputStream content, long size, String mediaType) {
        Path target = resolve(key);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
        try {
            // Clés réservées (branding/…) : sous-répertoire créé à la demande.
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                long copied = content.transferTo(out);
                if (size >= 0 && copied != size) {
                    throw new IllegalStateException(
                            "taille blob incohérente: attendu " + size + ", écrit " + copied);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("Écriture blob locale échouée: " + key, e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    @Override
    public Optional<BlobObject> get(String key, Optional<ByteRange> range) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            long total = Files.size(path);
            if (range.isEmpty()) {
                return Optional.of(new BlobObject(
                        Files.newInputStream(path), total, null, Optional.empty(), total));
            }
            ByteRange r = range.get();
            long from = r.from();
            long to = Math.min(r.to(), total - 1);
            if (from >= total) {
                return Optional.empty();
            }
            long length = to - from + 1;
            InputStream in = Files.newInputStream(path);
            long skipped = in.skip(from);
            if (skipped < from) {
                in.close();
                return Optional.empty();
            }
            return Optional.of(new BlobObject(
                    new LimitedInputStream(in, length),
                    length,
                    null,
                    Optional.of(new ByteRange(from, to)),
                    total));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture blob locale échouée: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Suppression blob locale échouée: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    Path resolve(String key) {
        String valid = BlobKeys.requireValid(key);
        Path resolved = root.resolve(valid).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("clé blob invalide (traversée refusée)");
        }
        return resolved;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Nettoyage temp blob ignoré: {}", path, e);
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long remaining) {
            this.in = in;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
