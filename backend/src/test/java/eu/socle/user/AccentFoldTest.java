// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccentFoldTest {

    @Test
    void foldsCaseAndAccents() {
        assertThat(AccentFold.fold("François")).isEqualTo("francois");
        assertThat(AccentFold.fold("ÉLÉONORE")).isEqualTo("eleonore");
        assertThat(AccentFold.likeContains("éa")).isEqualTo("%ea%");
    }
}
