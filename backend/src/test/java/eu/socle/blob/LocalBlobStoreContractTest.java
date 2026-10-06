// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalBlobStoreContractTest extends BlobStoreContractTest {

    @TempDir Path dir;
    private LocalBlobStore store;

    @BeforeEach
    void setUp() {
        store = new LocalBlobStore(dir);
    }

    @Override
    BlobStore store() {
        return store;
    }
}
