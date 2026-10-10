// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatTokenFormatTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void generate_format_prefixLookup12Secret43Base62() {
        PatTokenFormat.Generated g = PatTokenFormat.generate(random);

        assertThat(g.token()).matches("^pat_[0-9A-Za-z]{12}_[0-9A-Za-z]{43}$");
        assertThat(g.lookup()).hasSize(12);
        assertThat(g.secret()).hasSize(PatTokenFormat.SECRET_LENGTH);
        assertThat(g.token()).isEqualTo("pat_" + g.lookup() + "_" + g.secret());
        assertThat(g.last4()).isEqualTo(g.token().substring(g.token().length() - 4));
    }

    @Test
    void generate_secretCarries32RandomBytes_andValuesAreUnique() {
        Set<String> secrets = new HashSet<>();
        Set<String> lookups = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            PatTokenFormat.Generated g = PatTokenFormat.generate(random);
            secrets.add(g.secret());
            lookups.add(g.lookup());
        }
        assertThat(secrets).hasSize(500);
        assertThat(lookups).hasSize(500);
        // 62^43 ≥ 2^256 : 43 caractères base62 suffisent pour 32 octets.
        assertThat(Math.pow(62, PatTokenFormat.SECRET_LENGTH)).isGreaterThanOrEqualTo(Math.pow(2, 256));
        assertThat(PatTokenFormat.SECRET_BYTES).isGreaterThanOrEqualTo(32);
    }

    @Test
    void base62_fixedWidth_maxValueFits_overflowRejected() {
        byte[] max = new byte[32];
        java.util.Arrays.fill(max, (byte) 0xFF);
        assertThat(PatTokenFormat.base62(max, 43)).hasSize(43);
        assertThat(PatTokenFormat.base62(new byte[32], 43)).isEqualTo("0".repeat(43));
        assertThatThrownBy(() -> PatTokenFormat.base62(max, 42)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_roundTrip_andRejectsMalformed() {
        PatTokenFormat.Generated g = PatTokenFormat.generate(random);
        assertThat(PatTokenFormat.parse(g.token())).hasValueSatisfying(p -> {
            assertThat(p.lookup()).isEqualTo(g.lookup());
            assertThat(p.secret()).isEqualTo(g.secret());
        });

        assertThat(PatTokenFormat.parse(null)).isEmpty();
        assertThat(PatTokenFormat.parse("")).isEmpty();
        assertThat(PatTokenFormat.parse("pat_")).isEmpty();
        assertThat(PatTokenFormat.parse(g.token() + "x")).isEmpty();
        assertThat(PatTokenFormat.parse(g.token().substring(1))).isEmpty();
        assertThat(PatTokenFormat.parse("pat_" + g.lookup() + "-" + g.secret())).isEmpty();
        assertThat(PatTokenFormat.parse("pat_" + g.lookup() + "_" + g.secret().substring(1) + "+")).isEmpty();
        assertThat(PatTokenFormat.parse("eyJhbGciOiJSUzI1NiJ9.e30.sig")).isEmpty();
    }

    @Test
    void toString_neverExposesSecret() {
        PatTokenFormat.Generated g = PatTokenFormat.generate(random);
        assertThat(g.toString()).doesNotContain(g.secret()).doesNotContain(g.token());
        assertThat(PatTokenFormat.parse(g.token()).orElseThrow().toString()).doesNotContain(g.secret());
    }

    @Test
    void hmac_isKeyedBySecretAndPepper_notThePlainSecret() {
        PatHasher hasher = new PatHasher("p".repeat(32).getBytes(StandardCharsets.UTF_8));
        PatHasher other = new PatHasher("q".repeat(32).getBytes(StandardCharsets.UTF_8));
        PatTokenFormat.Generated g = PatTokenFormat.generate(random);

        byte[] h = hasher.hash(g.secret());
        assertThat(h).hasSize(32);
        assertThat(hasher.hash(g.secret())).isEqualTo(h);
        assertThat(other.hash(g.secret())).isNotEqualTo(h);
        assertThat(new String(h, StandardCharsets.ISO_8859_1)).doesNotContain(g.secret());
    }
}
