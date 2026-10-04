// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.attachment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPropertiesTest {

    @Test
    void maxBytesFor_usesDistinctVideoCeiling() {
        AttachmentProperties p = new AttachmentProperties();
        p.setMaxMb(25);
        p.setMaxVideoMb(200);
        assertThat(p.maxBytesFor("application/pdf")).isEqualTo(25L * 1024 * 1024);
        assertThat(p.maxBytesFor("video/mp4")).isEqualTo(200L * 1024 * 1024);
        assertThat(p.maxBytesFor("video/webm; codecs=vp9")).isEqualTo(200L * 1024 * 1024);
    }

    @Test
    void allowedMediaTypes_includeMp4AndWebm() {
        AttachmentProperties p = new AttachmentProperties();
        assertThat(p.isAllowed("video/mp4")).isTrue();
        assertThat(p.isAllowed("video/webm")).isTrue();
        assertThat(p.isAllowed("video/ogg")).isFalse();
    }
}
