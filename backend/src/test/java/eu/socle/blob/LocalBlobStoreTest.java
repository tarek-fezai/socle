// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.blob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBlobStoreTest {

    @TempDir Path dir;
    LocalBlobStore store;

    @BeforeEach
    void setUp() {
        store = new LocalBlobStore(dir);
    }

    @Test
    void putGetExistsDelete_roundTrip() throws Exception {
        String key = BlobKeys.newKey();
        byte[] data = "hello-blob".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(data), data.length, "text/plain");
        assertThat(store.exists(key)).isTrue();
        try (BlobStore.BlobObject obj = store.get(key, Optional.empty()).orElseThrow()) {
            assertThat(obj.content().readAllBytes()).isEqualTo(data);
            assertThat(obj.contentLength()).isEqualTo(data.length);
        }
        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    void put_isAtomic_targetAppearsOnlyAfterRename() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] data = "atomic".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(data), data.length, "text/plain");
        assertThat(Files.list(store.root())
                .filter(p -> p.getFileName().toString().startsWith(key + ".tmp-"))
                .findAny()).isEmpty();
        assertThat(Files.readAllBytes(store.root().resolve(key))).isEqualTo(data);
    }

    @Test
    void pathTraversal_isRejected() {
        assertThatThrownBy(() -> store.resolve("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversée");
        assertThatThrownBy(() -> store.resolve("..\\windows"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobKeys.requireValid("../../x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobKeys.requireValid("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void range_returnsPartialBytes() throws Exception {
        String key = BlobKeys.newKey();
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(data), data.length, "text/plain");
        try (BlobStore.BlobObject obj = store.get(key, Optional.of(new BlobStore.ByteRange(2, 5))).orElseThrow()) {
            assertThat(obj.content().readAllBytes()).isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
            assertThat(obj.totalSize()).isEqualTo(10);
            assertThat(obj.range()).contains(new BlobStore.ByteRange(2, 5));
        }
    }
}
