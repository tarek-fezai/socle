// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoragePropertiesTest {

    @Test
    void absentProvider_failsFast() {
        StorageProperties props = new StorageProperties();
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obligatoire");
    }

    @Test
    void invalidProvider_failsFast() {
        StorageProperties props = new StorageProperties();
        props.setProvider("hybrid");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalide");
    }

    @Test
    void relationalAndGit_accepted() {
        StorageProperties r = new StorageProperties();
        r.setProvider("relational");
        r.validate();

        StorageProperties g = new StorageProperties();
        g.setProvider("GIT");
        g.validate();
    }
}
