// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.linkpreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/x",
            "http://127.0.0.1/x",
            "http://[::1]/x",
            "http://10.1.2.3/meta",
            "http://169.254.169.254/latest/meta-data/",
            "http://192.168.0.1/",
            "http://172.16.5.5/",
            "http://example.com:8080/"
    })
    void rejectsUnsafeUrlsWithoutOutboundConnect(String url) {
        assertThatThrownBy(() -> {
            URI uri = SsrfGuard.requireHttpUrl(url);
            if ("example.com".equals(uri.getHost())) {
                return;
            }
            SsrfGuard.resolveAndRejectPrivate(uri.getHost());
        }).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void rejectsDomainOutsideWhitelist() {
        URI uri = SsrfGuard.requireHttpUrl("https://evil.example/path");
        assertThatThrownBy(() -> SsrfGuard.requireAllowedDomain(uri, Set.of("allowed.org")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("liste blanche");
    }

    @Test
    void allowsListedDomain() {
        URI uri = SsrfGuard.requireHttpUrl("https://docs.allowed.org/a");
        SsrfGuard.requireAllowedDomain(uri, Set.of("allowed.org"));
    }

    @Test
    void blocksPrivateInetAddresses() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("::1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void blocksBenchmarkingClassEAndBroadcast() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("198.18.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("198.19.255.255"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("240.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("255.255.255.255"))).isTrue();
    }

    @Test
    void blocksNat64And6to4EmbeddedPrivate() throws Exception {
        // 64:ff9b::10.0.0.1
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("64:ff9b::a00:1"))).isTrue();
        // 2002:0a00:0001:: = 6to4 of 10.0.0.1
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("2002:a00:1::"))).isTrue();
        // IPv4-mapped ::ffff:127.0.0.1
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("::ffff:127.0.0.1"))).isTrue();
    }

    @Test
    void resolveHostnameThatMapsToPrivate_isRejected() {
        assertThatThrownBy(() -> SsrfGuard.resolveAndRejectPrivate("localhost"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("privée");
    }
}
