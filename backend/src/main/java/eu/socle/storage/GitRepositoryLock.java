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
 * Limite documentée : le verrou n'est pas distribué hors processus locaux partageant le FS.
 */
final class GitRepositoryLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryLock.class);

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;
    private final Path lockPath;

    private GitRepositoryLock(RandomAccessFile raf, FileChannel channel, FileLock lock, Path lockPath) {
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
        this.lockPath = lockPath;
    }

    static GitRepositoryLock acquire(Path repoPath) {
        try {
            Files.createDirectories(repoPath);
            Path lockPath = repoPath.resolve(".socle-instance.lock");
            RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException overlapping) {
                raf.close();
                log.error(
                        "Une autre instance Socle détient déjà le verrou Git sur {} — "
                                + "mode git = 1 seule réplique backend (verrou JVM fichier)",
                        repoPath);
                throw new IllegalStateException(
                        "socle.storage.provider=git : instance concurrente détectée sur " + repoPath
                                + " (replicas>1 interdit — voir docs/operations/install.md)",
                        overlapping);
            }
            if (lock == null) {
                raf.close();
                log.error(
                        "Une autre instance Socle détient déjà le verrou Git sur {} — "
                                + "mode git = 1 seule réplique backend (verrou JVM fichier)",
                        repoPath);
                throw new IllegalStateException(
                        "socle.storage.provider=git : instance concurrente détectée sur " + repoPath
                                + " (replicas>1 interdit — voir docs/operations/install.md)");
            }
            String payload = "pid=" + ProcessHandle.current().pid() + " started=" + System.currentTimeMillis() + "\n";
            raf.setLength(0);
            raf.write(payload.getBytes(StandardCharsets.UTF_8));
            log.info("Verrou Git acquis: {}", lockPath);
            return new GitRepositoryLock(raf, channel, lock, lockPath);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible d'acquérir le verrou Git sur " + repoPath, e);
        }
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
