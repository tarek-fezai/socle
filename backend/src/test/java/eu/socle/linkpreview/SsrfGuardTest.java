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
                // port non 80/443 déjà refusé dans requireHttpUrl
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
    void resolveHostnameThatMapsToPrivate_isRejected() {
        // "localhost" résout toujours vers loopback — aucun connect TCP.
        assertThatThrownBy(() -> SsrfGuard.resolveAndRejectPrivate("localhost"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("privée");
    }
}
