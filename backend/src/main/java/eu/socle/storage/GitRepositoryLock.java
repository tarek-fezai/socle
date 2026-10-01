// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verrou fichier JVM sur le dépôt Git — une seule instance backend par dépôt.
 * Fichier sous {@code .git/socle-instance.lock} (hors working tree).
 * Limite documentée : le verrou n'est pas distribué hors processus locaux partageant le FS.
 */
final class GitRepositoryLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryLock.class);

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;

    private GitRepositoryLock(RandomAccessFile raf, FileChannel channel, FileLock lock) {
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * @param gitDir chemin du répertoire {@code .git} déjà initialisé
     */
    static GitRepositoryLock acquire(Path gitDir) {
        try {
            Files.createDirectories(gitDir);
            Path lockPath = gitDir.resolve("socle-instance.lock");
            RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException overlapping) {
                raf.close();
                failConcurrent(gitDir, overlapping);
                throw new IllegalStateException("unreachable");
            }
            if (lock == null) {
                raf.close();
                failConcurrent(gitDir, null);
            }
            String payload = "pid=" + ProcessHandle.current().pid() + " started=" + System.currentTimeMillis() + "\n";
            raf.setLength(0);
            raf.write(payload.getBytes(StandardCharsets.UTF_8));
            log.info("Verrou Git acquis: {}", lockPath);
            return new GitRepositoryLock(raf, channel, lock);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'acquérir le verrou Git sur " + gitDir, e);
        }
    }

    private static void failConcurrent(Path gitDir, Throwable cause) {
        log.error(
                "Une autre instance Socle détient déjà le verrou Git sur {} — "
                        + "mode git = 1 seule réplique backend (verrou JVM fichier)",
                gitDir);
        IllegalStateException ex = new IllegalStateException(
                "socle.storage.provider=git : instance concurrente détectée sur " + gitDir
                        + " (replicas>1 interdit — voir docs/operations/install.md)");
        if (cause != null) {
            ex.initCause(cause);
        }
        throw ex;
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            log.warn("Libération verrou Git: {}", e.toString());
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // ignore
        }
        try {
            raf.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
