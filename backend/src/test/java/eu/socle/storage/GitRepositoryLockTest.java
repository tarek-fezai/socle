// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryLockTest {

    @TempDir
    Path temp;

    @Test
    void secondAcquireFails() throws Exception {
        Path gitDir = temp.resolve(".git");
        Files.createDirectories(gitDir);
        GitRepositoryLock first = GitRepositoryLock.acquire(gitDir);
        try {
            assertThatThrownBy(() -> GitRepositoryLock.acquire(gitDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("instance concurrente");
        } finally {
            first.close();
        }
    }
}
