// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.blob;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrat commun {@link BlobStore} — exécuté contre {@link LocalBlobStore}
 * ({@code LocalBlobStoreContractTest}) ET {@link S3BlobStore} sur MinIO
 * ({@code S3BlobStoreContractTest}).
 */
abstract class BlobStoreContractTest {

    static final byte[] DIGITS = "0123456789".getBytes(StandardCharsets.UTF_8);

    /** Store sous test (instance prête à l'emploi, vide ou non — les clés sont des UUID uniques). */
    abstract BlobStore store();

    private void put(String key, byte[] data) {
        store().put(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");
    }

    private byte[] read(String key, Optional<BlobStore.ByteRange> range) throws Exception {
        try (BlobStore.BlobObject obj = store().get(key, range).orElseThrow()) {
            return obj.content().readAllBytes();
        }
    }

    @Test
    void put_get_exists_delete_roundTrip() throws Exception {
        String key = BlobKeys.newKey();
        byte[] data = "contract-blob".getBytes(StandardCharsets.UTF_8);

        assertThat(store().exists(key)).isFalse();
        put(key, data);
        assertThat(store().exists(key)).isTrue();

        try (BlobStore.BlobObject obj = store().get(key, Optional.empty()).orElseThrow()) {
            assertThat(obj.content().readAllBytes()).isEqualTo(data);
            assertThat(obj.contentLength()).isEqualTo(data.length);
            assertThat(obj.totalSize()).isEqualTo(data.length);
            assertThat(obj.range()).isEmpty();
        }

        store().delete(key);
        assertThat(store().exists(key)).isFalse();
        assertThat(store().get(key, Optional.empty())).isEmpty();
    }

    @Test
    void put_binaryContent_isByteExact() throws Exception {
        String key = BlobKeys.newKey();
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        put(key, data);
        assertThat(read(key, Optional.empty())).isEqualTo(data);
        store().delete(key);
    }

    @Test
    void put_sameKey_overwrites() throws Exception {
        String key = BlobKeys.newKey();
        put(key, "first".getBytes(StandardCharsets.UTF_8));
        put(key, "second-value".getBytes(StandardCharsets.UTF_8));
        assertThat(read(key, Optional.empty())).isEqualTo("second-value".getBytes(StandardCharsets.UTF_8));
        store().delete(key);
    }

    @Test
    void get_missingKey_isEmpty_andExistsFalse() {
        String key = BlobKeys.newKey();
        assertThat(store().get(key, Optional.empty())).isEmpty();
        assertThat(store().get(key, Optional.of(new BlobStore.ByteRange(0, 3)))).isEmpty();
        assertThat(store().exists(key)).isFalse();
    }

    @Test
    void delete_missingKey_isIdempotent() {
        String key = BlobKeys.newKey();
        assertThatCode(() -> store().delete(key)).doesNotThrowAnyException();
        assertThatCode(() -> store().delete(key)).doesNotThrowAnyException();
    }

    @Test
    void range_middle_returnsPartialBytes() throws Exception {
        String key = BlobKeys.newKey();
        put(key, DIGITS);
        try (BlobStore.BlobObject obj = store().get(key, Optional.of(new BlobStore.ByteRange(2, 5))).orElseThrow()) {
            assertThat(obj.content().readAllBytes()).isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
            assertThat(obj.contentLength()).isEqualTo(4);
            assertThat(obj.totalSize()).isEqualTo(10);
            assertThat(obj.range()).contains(new BlobStore.ByteRange(2, 5));
        }
        store().delete(key);
    }

    @Test
    void range_startOnly_clampedToEnd() throws Exception {
        String key = BlobKeys.newKey();
        put(key, DIGITS);
        // ByteRange(from, Long.MAX_VALUE - 1) = "bytes=7-" côté contrôleur.
        try (BlobStore.BlobObject obj = store()
                .get(key, Optional.of(new BlobStore.ByteRange(7, Long.MAX_VALUE - 1))).orElseThrow()) {
            assertThat(obj.content().readAllBytes()).isEqualTo("789".getBytes(StandardCharsets.UTF_8));
            assertThat(obj.contentLength()).isEqualTo(3);
            assertThat(obj.totalSize()).isEqualTo(10);
            assertThat(obj.range()).contains(new BlobStore.ByteRange(7, 9));
        }
        store().delete(key);
    }

    @Test
    void range_singleByte() throws Exception {
        String key = BlobKeys.newKey();
        put(key, DIGITS);
        assertThat(read(key, Optional.of(new BlobStore.ByteRange(0, 0))))
                .isEqualTo("0".getBytes(StandardCharsets.UTF_8));
        assertThat(read(key, Optional.of(new BlobStore.ByteRange(9, 9))))
                .isEqualTo("9".getBytes(StandardCharsets.UTF_8));
        store().delete(key);
    }

    @Test
    void range_startBeyondEnd_isEmpty() {
        String key = BlobKeys.newKey();
        put(key, DIGITS);
        assertThat(store().get(key, Optional.of(new BlobStore.ByteRange(10, 20)))).isEmpty();
        assertThat(store().get(key, Optional.of(new BlobStore.ByteRange(500, 600)))).isEmpty();
        store().delete(key);
    }

    @Test
    void invalidKeys_areRejected() {
        assertThatThrownBy(() -> store().exists("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().get("not-a-uuid", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().delete("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> put("..\\x", DIGITS)).isInstanceOf(IllegalArgumentException.class);
    }
}
