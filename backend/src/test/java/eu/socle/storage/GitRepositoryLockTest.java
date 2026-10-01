// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRepositoryLockTest {

    @TempDir
    Path temp;

    @Test
    void secondAcquireFails() {
        GitRepositoryLock first = GitRepositoryLock.acquire(temp);
        try {
            assertThatThrownBy(() -> GitRepositoryLock.acquire(temp))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("instance concurrente");
        } finally {
            first.close();
        }
    }
}
