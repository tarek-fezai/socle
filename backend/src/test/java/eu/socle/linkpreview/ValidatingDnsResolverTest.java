// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.linkpreview;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatingDnsResolverTest {

    @Test
    void mixedPublicAndLoopback_isRejected_noSafeSubset() throws Exception {
        InetAddress pub = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(h -> new InetAddress[]{pub, loop});
        assertThatThrownBy(() -> resolver.resolve("evil.test"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("privée");
    }

    @Test
    void rebindingSecondLookupPrivate_isRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InetAddress pub = InetAddress.getByAddress(new byte[]{1, 2, 3, 4});
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(h -> {
            if (calls.getAndIncrement() == 0) {
                return new InetAddress[]{pub};
            }
            return new InetAddress[]{loop};
        });
        assertThat(resolver.resolve("rebinding.test")).containsExactly(pub);
        assertThatThrownBy(() -> resolver.resolve("rebinding.test"))
                .isInstanceOf(UnknownHostException.class);
        // Deuxième réponse (127.0.0.1) jamais renvoyée au client HTTP.
    }

    @Test
    void onlyValidatedAddressesReturned() throws Exception {
        InetAddress pub = InetAddress.getByAddress(new byte[]{8, 8, 4, 4});
        ValidatingDnsResolver resolver = new ValidatingDnsResolver(h -> new InetAddress[]{pub});
        assertThat(resolver.resolve("ok.test")).containsExactly(pub);
    }
}
