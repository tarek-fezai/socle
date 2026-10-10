// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatHasherTest {

    private static final byte[] PEPPER = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void hash_isStandardHmacSha256() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PEPPER, "HmacSHA256"));
        byte[] expected = mac.doFinal("secret".getBytes(StandardCharsets.UTF_8));

        assertThat(new PatHasher(PEPPER).hash("secret")).isEqualTo(expected);
    }

    @Test
    void matches_trueOnlyForSameSecret_falseForUnknownLookup() {
        PatHasher hasher = new PatHasher(PEPPER);
        byte[] stored = hasher.hash("good");

        assertThat(hasher.matches("good", stored)).isTrue();
        assertThat(hasher.matches("bad", stored)).isFalse();
        assertThat(hasher.matches("good", null)).isFalse();
        assertThat(hasher.matches("good", new byte[0])).isFalse();
    }

    @Test
    void comparison_usesConstantTimeMessageDigestIsEqual() throws Exception {
        String source = Files.readString(Path.of("src/main/java/eu/socle/pat/PatHasher.java"));
        assertThat(source).contains("MessageDigest.isEqual(");
        assertThat(source).doesNotContain("Arrays.equals(");
    }

    @Test
    void pepperShorterThan32Bytes_rejected() {
        assertThatThrownBy(() -> new PatHasher(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PatHasher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PatHasher(new byte[32])).isNotNull();
    }

    @Test
    void toString_neverExposesPepper() {
        assertThat(new PatHasher(PEPPER).toString()).doesNotContain(new String(PEPPER, StandardCharsets.UTF_8));
    }
}
